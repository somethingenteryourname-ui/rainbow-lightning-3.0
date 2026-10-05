package me.rainbowlightning;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Detects "holding right-click" and drives the charge + rainbow lightning beam.
 *
 * Minecraft has no "right-click released" packet for normal items, but while you HOLD
 * right-click the client re-sends the click every 4 ticks. So we treat the player as
 * holding as long as clicks keep arriving, and stop a few ticks after they stop.
 */
public final class BeamManager implements Listener {

    private static final int RELEASE_GRACE_TICKS = 7;
    public static final double MIN_SIZE = 0.25;
    public static final double MAX_SIZE = 10.0;

    private final RainbowLightningPlugin plugin;
    private final RodItem rodItem;
    private final ScorchManager scorch;
    private final Map<UUID, BeamState> states = new HashMap<>();
    private final List<TempBolt> skyBolts = new ArrayList<>();

    private BukkitTask task;
    private long tick;

    // settings
    private int chargeTicks;
    private double size;
    private double range;
    private double hitRadius;
    private double damage;
    private int damageInterval;
    private boolean setOnFire;
    private boolean slowTargets;
    private boolean glowOutline;
    private boolean skyStrikes;
    private int skyStrikeInterval;
    private double rainbowSpeed;

    private record Fork(int node, Vector dir, Vector kink, double length) {}

    private record TempBolt(BoltRenderer renderer, long removeAtTick) {}

    private static final class BeamState {
        final long startTick;
        long lastClickTick;
        boolean firing;
        long firingTicks;
        final BoltRenderer renderer = new BoltRenderer();
        double[][] offsets;            // zig-zag shape of the bolt
        List<Fork> forks = new ArrayList<>();

        BeamState(long now) {
            this.startTick = now;
            this.lastClickTick = now;
        }
    }

    public BeamManager(RainbowLightningPlugin plugin, RodItem rodItem, ScorchManager scorch) {
        this.plugin = plugin;
        this.rodItem = rodItem;
        this.scorch = scorch;
    }

    public void loadSettings(FileConfiguration c) {
        chargeTicks = Math.max(1, c.getInt("charge-ticks", 20));
        size = clampSize(c.getDouble("beam.size", 1.0));
        range = c.getDouble("beam.range", 48);
        hitRadius = c.getDouble("beam.hit-radius", 1.0);
        damage = c.getDouble("beam.damage", 2.5);
        damageInterval = Math.max(1, c.getInt("beam.damage-interval-ticks", 4));
        setOnFire = c.getBoolean("beam.set-on-fire", true);
        slowTargets = c.getBoolean("beam.slow-targets", true);
        glowOutline = c.getBoolean("beam.glow-outline", true);
        skyStrikes = c.getBoolean("beam.lightning-strike-effect", true);
        skyStrikeInterval = Math.max(1, c.getInt("beam.lightning-strike-interval-ticks", 15));
        rainbowSpeed = c.getDouble("beam.rainbow-speed", 0.02);
    }

    public static double clampSize(double s) {
        return Math.max(MIN_SIZE, Math.min(MAX_SIZE, s));
    }

    public double getSize() {
        return size;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 1L, 1L);
    }

    public void stopAll() {
        if (task != null) task.cancel();
        for (BeamState s : states.values()) s.renderer.remove();
        states.clear();
        for (TempBolt b : skyBolts) b.renderer().remove();
        skyBolts.clear();
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        if (!rodItem.isRod(event.getItem())) return;

        // never place the rod / open doors etc. while using it
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);

        Player player = event.getPlayer();
        if (player.hasPermission("rainbowlightning.use")) heartbeat(player);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        if (!rodItem.isRod(player.getInventory().getItemInMainHand())) return;
        event.setCancelled(true);
        if (player.hasPermission("rainbowlightning.use")) heartbeat(player);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (rodItem.isRod(event.getItemInHand())) event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        BeamState s = states.remove(event.getPlayer().getUniqueId());
        if (s != null) s.renderer.remove();
    }

    private void heartbeat(Player player) {
        BeamState state = states.get(player.getUniqueId());
        if (state == null) {
            state = new BeamState(tick);
            states.put(player.getUniqueId(), state);
            player.getWorld().playSound(player.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 1.6f);
        }
        state.lastClickTick = tick;
    }

    // ------------------------------------------------------------------ main loop

    private void tickAll() {
        tick++;

        // clean up finished sky bolts
        Iterator<TempBolt> it = skyBolts.iterator();
        while (it.hasNext()) {
            TempBolt b = it.next();
            if (tick >= b.removeAtTick()) {
                b.renderer().remove();
                it.remove();
            }
        }

        for (UUID id : new ArrayList<>(states.keySet())) {
            BeamState state = states.get(id);
            if (state == null) continue;
            Player player = Bukkit.getPlayer(id);

            boolean stillHolding = player != null
                    && player.isOnline()
                    && !player.isDead()
                    && rodItem.isRod(player.getInventory().getItemInMainHand())
                    && tick - state.lastClickTick <= RELEASE_GRACE_TICKS;

            if (!stillHolding) {
                if (player != null && state.firing) {
                    player.getWorld().playSound(player.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1f, 1.4f);
                }
                if (player != null) player.sendActionBar(Component.empty());
                state.renderer.remove();
                states.remove(id);
                continue;
            }

            long held = tick - state.startTick;
            if (held < chargeTicks) {
                charge(player, held);
            } else {
                if (!state.firing) {
                    state.firing = true;
                    World w = player.getWorld();
                    w.playSound(player.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.2f, 1.3f);
                    w.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.8f);
                    player.sendActionBar(MiniMessage.miniMessage().deserialize("<bold><rainbow>⚡ UNLEASHED ⚡</rainbow></bold>"));
                }
                state.firingTicks++;
                fire(player, state);
            }
        }
    }

    // ------------------------------------------------------------------ charging

    private void charge(Player player, long held) {
        double progress = held / (double) chargeTicks;
        World w = player.getWorld();
        Location hand = handLocation(player);

        if (progress > 0.2) {
            w.spawnParticle(Particle.ELECTRIC_SPARK, hand, 1 + (int) (progress * 5), 0.15, 0.15, 0.15, 0.05, null, true);
        }
        if (held % 4 == 0) {
            float pitch = (float) Math.min(2.0, 0.6 + progress * 1.4);
            w.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, pitch);
        }

        int filled = (int) Math.round(progress * 10);
        String bar = "<rainbow>" + "▮".repeat(filled) + "</rainbow><dark_gray>" + "▮".repeat(10 - filled) + "</dark_gray>";
        player.sendActionBar(MiniMessage.miniMessage().deserialize("<gray>Charging </gray>" + bar));
    }

    // ------------------------------------------------------------------ the beam

    private void fire(Player player, BeamState state) {
        World w = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        ThreadLocalRandom r = ThreadLocalRandom.current();

        // where the cursor is pointing
        RayTraceResult hit = w.rayTraceBlocks(eye, dir, range, FluidCollisionMode.NEVER, true);
        Location end;
        Block hitBlock = null;
        if (hit != null) {
            end = hit.getHitPosition().toLocation(w);
            hitBlock = hit.getHitBlock();
        } else {
            end = eye.clone().add(dir.clone().multiply(range));
        }

        // start in front of the rod (further out when the bolt is huge so it doesn't fill your screen)
        Location start = handLocation(player).add(dir.clone().multiply(Math.max(0, size - 1) * 0.35));
        Vector startV = start.toVector();
        Vector axis = end.toVector().subtract(startV);
        double len = axis.length();
        if (len < 0.5) return;
        Vector f = axis.clone().multiply(1.0 / len);
        Vector ref = Math.abs(f.getY()) < 0.95 ? new Vector(0, 1, 0) : new Vector(1, 0, 0);
        Vector u = f.getCrossProduct(ref).normalize();
        Vector v = f.getCrossProduct(u).normalize();

        // re-shape the zig-zag every 2 ticks so it crackles like real lightning
        int nodes = Math.max(3, Math.min(40, (int) (len / 2.2)));
        if (state.offsets == null || state.offsets.length != nodes + 1 || state.firingTicks % 2 == 1) {
            state.offsets = new double[nodes + 1][2];
            for (int i = 1; i < nodes; i++) {
                state.offsets[i][0] = r.nextDouble() * 2 - 1;
                state.offsets[i][1] = r.nextDouble() * 2 - 1;
            }
            state.forks = new ArrayList<>();
            int forkCount = 1 + (int) (len / 12);
            for (int i = 0; i < forkCount; i++) {
                if (r.nextDouble() < 0.35) continue;
                Vector fd = f.clone().multiply(0.7).add(randomUnit(r)).normalize();
                Vector kink = fd.clone().add(randomUnit(r).multiply(0.8)).normalize();
                state.forks.add(new Fork(1 + r.nextInt(Math.max(1, nodes - 1)), fd, kink, 1.2 + r.nextDouble() * 2.3));
            }
        }

        double jitter = 0.75 * Math.sqrt(size);
        Vector[] pts = new Vector[nodes + 1];
        for (int i = 0; i <= nodes; i++) {
            double t = (double) i / nodes;
            double scale = jitter * Math.min(1.0, Math.sin(Math.PI * t) * 2.5); // pinned at both ends
            pts[i] = startV.clone().add(f.clone().multiply(len * t))
                    .add(u.clone().multiply(state.offsets[i][0] * scale))
                    .add(v.clone().multiply(state.offsets[i][1] * scale));
        }

        double baseHue = tick * rainbowSpeed;
        double coreW = 0.16 * size;
        double shellW = 0.42 * size;

        List<BoltRenderer.Seg> segs = new ArrayList<>();
        for (int i = 0; i < nodes; i++) {
            double hue = baseHue + ((double) i / nodes) * 0.5; // rainbow flows along the bolt
            segs.add(new BoltRenderer.Seg(pts[i], pts[i + 1], hue, coreW, shellW));
        }
        double forkScale = Math.sqrt(size);
        for (Fork fork : state.forks) {
            if (fork.node() >= pts.length) continue;
            Vector a = pts[fork.node()];
            Vector b = a.clone().add(fork.dir().clone().multiply(fork.length() * forkScale));
            Vector c = b.clone().add(fork.kink().clone().multiply(fork.length() * 0.6 * forkScale));
            double hue = baseHue + ((double) fork.node() / nodes) * 0.5;
            segs.add(new BoltRenderer.Seg(a, b, hue, coreW * 0.6, shellW * 0.55));
            segs.add(new BoltRenderer.Seg(b, c, hue, coreW * 0.45, shellW * 0.4));
        }
        state.renderer.render(w, segs, glowOutline);

        impactEffects(w, end, hitBlock, state, baseHue);

        // looping beam sound
        if (state.firingTicks % 8 == 1) {
            w.playSound(player.getLocation(), Sound.BLOCK_BEACON_AMBIENT, 1f, 2f);
        }

        // hurt everything the beam touches (bigger lightning = wider hit area)
        if (state.firingTicks % damageInterval == 1 || damageInterval == 1) {
            double reach = hitRadius + (size - 1) * 0.6;
            BoundingBox area = BoundingBox.of(start, end).expand(reach + 1.0);
            for (Entity e : w.getNearbyEntities(area)) {
                if (!(e instanceof LivingEntity target) || e.equals(player) || target.isDead()) continue;
                if (e instanceof Player tp && (tp.getGameMode() == GameMode.CREATIVE || tp.getGameMode() == GameMode.SPECTATOR)) continue;
                if (target.getBoundingBox().expand(reach).rayTrace(startV, f, len) == null) continue;
                zap(player, target);
            }
        }
    }

    private void impactEffects(World w, Location end, Block hitBlock, BeamState state, double hue) {
        double s = Math.sqrt(size);
        w.spawnParticle(Particle.ELECTRIC_SPARK, end, 6, 0.5 * s, 0.5 * s, 0.5 * s, 0.25, null, true);
        if (state.firingTicks % 2 == 0) {
            w.spawnParticle(Particle.LARGE_SMOKE, end, 2, 0.4 * s, 0.3, 0.4 * s, 0.02, null, true);
        }
        if (state.firingTicks % 4 == 0) {
            w.spawnParticle(Particle.LAVA, end, 1, 0.3 * s, 0.1, 0.3 * s, 0, null, true);
        }
        if (state.firingTicks % 6 == 0) {
            w.playSound(end, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 1.2f, 0.8f + ThreadLocalRandom.current().nextFloat() * 0.6f);
        }

        if (hitBlock == null) return;

        if (state.firingTicks % 3 == 0) {
            scorch.scorch(hitBlock, (size - 1) * 0.6);
        }
        if (skyStrikes && state.firingTicks % skyStrikeInterval == 0) {
            skyStrike(w, end, hue + 0.3);
        }
    }

    /** A rainbow lightning bolt crashing down from the sky onto the impact spot. */
    private void skyStrike(World w, Location target, double hue) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Vector bottom = target.toVector();
        Vector top = bottom.clone().add(new Vector(r.nextDouble(-4, 4), 30, r.nextDouble(-4, 4)));
        int nodes = 14;
        double jitter = 1.2 * Math.sqrt(size);

        Vector[] pts = new Vector[nodes + 1];
        for (int i = 0; i <= nodes; i++) {
            double t = (double) i / nodes;
            Vector p = top.clone().add(bottom.clone().subtract(top).multiply(t));
            if (i != 0 && i != nodes) p.add(new Vector(r.nextDouble(-jitter, jitter), 0, r.nextDouble(-jitter, jitter)));
            pts[i] = p;
        }

        double coreW = 0.22 * size;
        double shellW = 0.55 * size;
        List<BoltRenderer.Seg> segs = new ArrayList<>();
        for (int i = 0; i < nodes; i++) {
            segs.add(new BoltRenderer.Seg(pts[i], pts[i + 1], hue + ((double) i / nodes) * 0.6, coreW, shellW));
        }
        BoltRenderer renderer = new BoltRenderer();
        renderer.render(w, segs, glowOutline);
        skyBolts.add(new TempBolt(renderer, tick + 6));

        w.playSound(target, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.5f, 1.2f + r.nextFloat() * 0.4f);
    }

    private void zap(Player shooter, LivingEntity target) {
        Vector velocity = target.getVelocity();

        // ignore the normal half-second "invincibility" after getting hit,
        // so the beam keeps chewing through health (and totems)
        target.setNoDamageTicks(0);
        DamageSource source = DamageSource.builder(DamageType.MAGIC)
                .withCausingEntity(shooter)
                .withDirectEntity(shooter)
                .build();
        target.damage(damage, source);

        // cancel the knockback so the target stays stuck in the beam
        if (!target.isDead()) target.setVelocity(velocity);

        if (setOnFire) target.setFireTicks(Math.max(target.getFireTicks(), 60));
        if (slowTargets) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20, 2, true, false, false));
        }

        Location c = target.getLocation().add(0, target.getHeight() / 2, 0);
        target.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, c, 10,
                target.getWidth() / 2, target.getHeight() / 2, target.getWidth() / 2, 0.3, null, true);
    }

    // ------------------------------------------------------------------ helpers

    /** Roughly where the tip of the rod is in the player's right hand. */
    private Location handLocation(Player player) {
        Location eye = player.getEyeLocation();
        double yaw = Math.toRadians(eye.getYaw());
        Vector right = new Vector(-Math.cos(yaw), 0, -Math.sin(yaw));
        return eye.clone()
                .add(eye.getDirection().multiply(0.6))
                .add(right.multiply(0.35))
                .add(0, -0.3, 0);
    }

    private static Vector randomUnit(ThreadLocalRandom r) {
        Vector v = new Vector(r.nextDouble() * 2 - 1, r.nextDouble() * 2 - 1, r.nextDouble() * 2 - 1);
        return v.lengthSquared() < 1e-6 ? new Vector(0, 1, 0) : v.normalize();
    }
}
