package me.rainbowlightning;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class RodCommand implements CommandExecutor, TabCompleter {

    private final RainbowLightningPlugin plugin;
    private final RodItem rodItem;

    public RodCommand(RainbowLightningPlugin plugin, RodItem rodItem) {
        this.plugin = plugin;
        this.rodItem = rodItem;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("rainbowlightning.reload")) {
                sender.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
                return true;
            }
            plugin.reloadSettings();
            sender.sendMessage(Component.text("RainbowLightning config reloaded.", NamedTextColor.GREEN));
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("size")) {
            if (!sender.hasPermission("rainbowlightning.size")) {
                sender.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
                return true;
            }
            if (args.length == 1) {
                sender.sendMessage(Component.text("Lightning size is " + format(plugin.getBeamManager().getSize())
                        + ". Use /" + label + " size <" + format(BeamManager.MIN_SIZE) + "-" + format(BeamManager.MAX_SIZE) + ">", NamedTextColor.YELLOW));
                return true;
            }
            double newSize;
            try {
                newSize = Double.parseDouble(args[1]);
            } catch (NumberFormatException e) {
                sender.sendMessage(Component.text("That's not a number: " + args[1], NamedTextColor.RED));
                return true;
            }
            newSize = BeamManager.clampSize(newSize);
            plugin.getConfig().set("beam.size", newSize);
            plugin.saveConfig();
            plugin.reloadSettings();
            sender.sendMessage(Component.text("Lightning size set to " + format(newSize) + ".", NamedTextColor.GREEN));
            return true;
        }

        if (!sender.hasPermission("rainbowlightning.give")) {
            sender.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
            return true;
        }

        Player target;
        if (args.length >= 1) {
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(Component.text("Player not found: " + args[0], NamedTextColor.RED));
                return true;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage(Component.text("Usage: /" + label + " <player>", NamedTextColor.RED));
            return true;
        }

        Map<Integer, ItemStack> leftover = target.getInventory().addItem(rodItem.create());
        for (ItemStack extra : leftover.values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), extra);
        }

        target.sendMessage(Component.text("You received the Rainbow Storm Rod! Hold right-click to fire.", NamedTextColor.LIGHT_PURPLE));
        if (!target.equals(sender)) {
            sender.sendMessage(Component.text("Gave the Rainbow Storm Rod to " + target.getName() + ".", NamedTextColor.GREEN));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String typed = args[0].toLowerCase();
            if (sender.hasPermission("rainbowlightning.reload") && "reload".startsWith(typed)) out.add("reload");
            if (sender.hasPermission("rainbowlightning.size") && "size".startsWith(typed)) out.add("size");
            if (sender.hasPermission("rainbowlightning.give")) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase().startsWith(typed)) out.add(p.getName());
                }
            }
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("size") && sender.hasPermission("rainbowlightning.size")) {
            for (String s : new String[]{"0.5", "1", "2", "3", "5", "10"}) {
                if (s.startsWith(args[1])) out.add(s);
            }
        }
        return out;
    }

    private static String format(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(Math.round(d * 100) / 100.0);
    }
}
