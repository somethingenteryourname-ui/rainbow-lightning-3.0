package me.rainbowlightning;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Draws a lightning bolt out of real 3D pieces instead of particles.
 *
 * Every zig-zag segment of the bolt is two stretched "block display" entities:
 *  - a solid, full-bright colored core
 *  - a slightly bigger see-through stained-glass shell with a colored glow outline
 *
 * The pieces are reused every tick (just moved/recolored), so it stays smooth and cheap.
 */
final class BoltRenderer {

    /** One straight piece of the bolt. */
    record Seg(Vector a, Vector b, double hue, double coreWidth, double shellWidth) {}

    // rainbow order, with the hue each color sits at
    private static final float[] HUES = {0.00f, 0.07f, 0.15f, 0.24f, 0.33f, 0.48f, 0.55f, 0.65f, 0.76f, 0.84f, 0.93f};
    private static final Material[] CORE = {
            Material.RED_CONCRETE, Material.ORANGE_CONCRETE, Material.YELLOW_CONCRETE, Material.LIME_CONCRETE,
            Material.GREEN_CONCRETE, Material.CYAN_CONCRETE, Material.LIGHT_BLUE_CONCRETE, Material.BLUE_CONCRETE,
            Material.PURPLE_CONCRETE, Material.MAGENTA_CONCRETE, Material.PINK_CONCRETE
    };
    private static final Material[] SHELL = {
            Material.RED_STAINED_GLASS, Material.ORANGE_STAINED_GLASS, Material.YELLOW_STAINED_GLASS,
            Material.LIME_STAINED_GLASS, Material.GREEN_STAINED_GLASS, Material.CYAN_STAINED_GLASS,
            Material.LIGHT_BLUE_STAINED_GLASS, Material.BLUE_STAINED_GLASS, Material.PURPLE_STAINED_GLASS,
            Material.MAGENTA_STAINED_GLASS, Material.PINK_STAINED_GLASS
    };
    private static final Map<Material, BlockData> DATA = new EnumMap<>(Material.class);
    private static final Vector3f ZERO = new Vector3f();

    private final List<BlockDisplay> cores = new ArrayList<>();
    private final List<BlockDisplay> shells = new ArrayList<>();

    void render(World w, List<Seg> segs, boolean glow) {
        // make sure we have enough pieces
        while (cores.size() < segs.size()) {
            Vector at = segs.get(cores.size()).a();
            cores.add(spawn(w, at));
            shells.add(spawn(w, at));
        }

        for (int i = 0; i < cores.size(); i++) {
            BlockDisplay core = valid(cores, i, w, segs);
            BlockDisplay shell = valid(shells, i, w, segs);

            if (i >= segs.size()) { // spare pieces this tick: shrink to nothing
                hide(core);
                hide(shell);
                continue;
            }

            Seg s = segs.get(i);
            Vector d = s.b().clone().subtract(s.a());
            float len = (float) d.length();
            if (len < 0.01f) {
                hide(core);
                hide(shell);
                continue;
            }
            Quaternionf q = new Quaternionf().rotationTo(
                    new Vector3f(0, 0, 1),
                    new Vector3f((float) d.getX(), (float) d.getY(), (float) d.getZ()).normalize());

            int idx = colorIndex(s.hue());
            // pieces are stretched a little past both ends so the zig-zag corners have no gaps
            float coreW = (float) s.coreWidth();
            place(core, w, s.a(), q, len, coreW, coreW * 0.5f, CORE[idx], null);
            if (s.shellWidth() > 0) {
                float shellW = (float) s.shellWidth();
                place(shell, w, s.a(), q, len, shellW, shellW * 0.5f, SHELL[idx], glow ? rainbow(s.hue()) : null);
            } else {
                hide(shell);
            }
        }
    }

    void remove() {
        for (BlockDisplay d : cores) if (d.isValid()) d.remove();
        for (BlockDisplay d : shells) if (d.isValid()) d.remove();
        cores.clear();
        shells.clear();
    }

    // ------------------------------------------------------------------

    /** Chunk unloads etc. can kill a piece - quietly replace it. */
    private BlockDisplay valid(List<BlockDisplay> list, int i, World w, List<Seg> segs) {
        BlockDisplay d = list.get(i);
        if (d.isValid() && d.getWorld().equals(w)) return d;
        if (d.isValid()) d.remove();
        Vector at = i < segs.size() ? segs.get(i).a() : segs.isEmpty() ? new Vector() : segs.get(0).a();
        BlockDisplay fresh = spawn(w, at);
        list.set(i, fresh);
        return fresh;
    }

    private static BlockDisplay spawn(World w, Vector at) {
        return w.spawn(new Location(w, at.getX(), at.getY(), at.getZ()), BlockDisplay.class, d -> {
            d.setPersistent(false);                         // never saved into the world
            d.setBrightness(new Display.Brightness(15, 15)); // full bright, glows at night
            d.setShadowRadius(0f);
            d.setShadowStrength(0f);
            d.setViewRange(4f);                             // visible from far away
            d.setInterpolationDuration(0);
            d.setTeleportDuration(0);
            d.setBlock(data(Material.WHITE_CONCRETE));
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(), new Quaternionf()));
        });
    }

    private static void place(BlockDisplay d, World w, Vector a, Quaternionf q, float len, float width,
                              float overlap, Material mat, Color glowColor) {
        d.teleport(new Location(w, a.getX(), a.getY(), a.getZ()));
        // a block display is a 1x1x1 box starting at its corner; stretch it along the segment and center it
        Vector3f offset = new Vector3f(-width / 2f, -width / 2f, -overlap / 2f).rotate(q);
        d.setTransformation(new Transformation(offset, new Quaternionf(q),
                new Vector3f(width, width, len + overlap), new Quaternionf()));

        if (d.getBlock().getMaterial() != mat) d.setBlock(data(mat));

        if (glowColor != null) {
            if (!d.isGlowing()) d.setGlowing(true);
            d.setGlowColorOverride(glowColor);
        } else if (d.isGlowing()) {
            d.setGlowing(false);
        }
    }

    private static void hide(BlockDisplay d) {
        d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(ZERO), new Quaternionf()));
        if (d.isGlowing()) d.setGlowing(false);
    }

    private static BlockData data(Material m) {
        return DATA.computeIfAbsent(m, Material::createBlockData);
    }

    private static int colorIndex(double hue) {
        float h = (float) (hue - Math.floor(hue));
        int best = 0;
        float bestDist = 2f;
        for (int i = 0; i < HUES.length; i++) {
            float dist = Math.abs(h - HUES[i]);
            dist = Math.min(dist, 1f - dist); // hue wraps around
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return best;
    }

    static Color rainbow(double hue) {
        float h = (float) (hue - Math.floor(hue));
        int rgb = java.awt.Color.HSBtoRGB(h, 1f, 1f);
        return Color.fromRGB(rgb & 0xFFFFFF);
    }
}
