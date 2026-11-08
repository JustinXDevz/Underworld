package dev.underworld;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** /underworld tp | info | reload | locate <structure> [tp] | biome <zone> [tp] */
public final class UnderworldCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("locate", "biome", "info", "tp", "reload");
    private static final List<String> STRUCTURES = List.of("castle", "kingdom", "outpost", "tower", "house",
            "obelisk", "fortress", "maze", "ruin", "chapel", "shrine", "pavilion", "stonecircle");
    private static final List<String> BIOMES = List.of("hub", "crystal", "crack", "snow", "dark");

    private final UnderworldPlugin plugin;

    public UnderworldCommand(UnderworldPlugin plugin) {
        this.plugin = plugin;
    }

    private static Component msg(String text, NamedTextColor color) {
        return Component.text(text, color);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "info" : args[0].toLowerCase();
        switch (sub) {
            case "reload" -> {
                if (!sender.hasPermission("underworld.admin")) {
                    sender.sendMessage(msg("No permission.", NamedTextColor.RED));
                    return true;
                }
                plugin.reload();
                sender.sendMessage(msg("Underworld config reloaded.", NamedTextColor.GREEN));
            }
            case "tp" -> {
                if (!sender.hasPermission("underworld.admin")) {
                    sender.sendMessage(msg("No permission.", NamedTextColor.RED));
                } else if (sender instanceof Player p) {
                    plugin.transition().sendDown(p);
                } else {
                    sender.sendMessage("Players only.");
                }
            }
            case "locate" -> locate(sender, args);
            case "biome" -> locateBiome(sender, args);
            default -> {
                World w = Bukkit.getWorld(plugin.underworldName());
                sender.sendMessage(msg("Underworld world: " + plugin.underworldName()
                        + (w == null ? " (not loaded)" : " (loaded)")
                        + " | Nether world: " + plugin.netherName(), NamedTextColor.GOLD));
                sender.sendMessage(msg("Usage: /underworld <locate|biome|info|tp|reload>", NamedTextColor.GRAY));
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ locate structure

    private Predicate<Terrain.Feature> matcher(String name) {
        return switch (name) {
            case "castle" -> f -> f.type() == Terrain.FeatureType.MEGA_CASTLE;
            case "kingdom" -> f -> f.type() == Terrain.FeatureType.KINGDOM;
            case "outpost" -> f -> f.type() == Terrain.FeatureType.OUTPOST;
            case "tower" -> f -> f.type() == Terrain.FeatureType.TOWER;
            case "house" -> f -> f.type() == Terrain.FeatureType.HOUSE;
            case "obelisk" -> f -> f.type() == Terrain.FeatureType.OBELISK;
            case "fortress" -> f -> f.type() == Terrain.FeatureType.FORTRESS;
            case "maze" -> f -> f.type() == Terrain.FeatureType.MAZE;
            case "ruin" -> f -> f.type() == Terrain.FeatureType.RUIN;
            case "chapel" -> f -> f.type() == Terrain.FeatureType.RUIN && f.zone() == Terrain.Zone.BASE;
            case "shrine" -> f -> f.type() == Terrain.FeatureType.RUIN && f.zone() == Terrain.Zone.CRYSTAL;
            case "pavilion" -> f -> f.type() == Terrain.FeatureType.RUIN && f.zone() == Terrain.Zone.CRACK;
            case "stonecircle" -> f -> f.type() == Terrain.FeatureType.RUIN && f.zone() == Terrain.Zone.SNOW;
            default -> null;
        };
    }

    private void locate(CommandSender sender, String[] args) {
        if (!sender.hasPermission("underworld.locate")) {
            sender.sendMessage(msg("No permission.", NamedTextColor.RED));
            return;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return;
        }
        World w = Bukkit.getWorld(plugin.underworldName());
        if (w == null) {
            sender.sendMessage(msg("The Underworld is not loaded.", NamedTextColor.RED));
            return;
        }
        Predicate<Terrain.Feature> match = args.length >= 2 ? matcher(args[1].toLowerCase()) : null;
        if (match == null) {
            sender.sendMessage(msg("Usage: /underworld locate <" + String.join("|", STRUCTURES) + "> [tp]",
                    NamedTextColor.GRAY));
            return;
        }
        String name = args[1].toLowerCase();
        boolean mega = name.equals("castle") || name.equals("kingdom");
        int ox = 0;
        int oz = 0;
        if (p.getWorld().equals(w)) {
            ox = p.getLocation().getBlockX();
            oz = p.getLocation().getBlockZ();
        }
        Terrain t = Terrain.get(w.getSeed());
        Terrain.Feature f = t.findStructure(ox, oz, mega, mega ? 6 : 40, match);
        if (f == null) {
            sender.sendMessage(msg("No " + name + " found nearby.", NamedTextColor.RED));
            return;
        }
        long dist = Math.round(Math.hypot(f.x() - ox, f.z() - oz));
        sender.sendMessage(msg("Nearest " + name + ": " + f.x() + ", " + (f.baseY() + 1) + ", " + f.z()
                + "  (about " + dist + " blocks away)", NamedTextColor.GREEN));
        if (args.length >= 3 && args[2].equalsIgnoreCase("tp") && p.hasPermission("underworld.admin")) {
            p.teleportAsync(new Location(w, f.x() + 0.5, f.baseY() + 2, f.z() + 0.5));
        }
    }

    // ------------------------------------------------------------------ locate biome

    private void locateBiome(CommandSender sender, String[] args) {
        if (!sender.hasPermission("underworld.locate")) {
            sender.sendMessage(msg("No permission.", NamedTextColor.RED));
            return;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return;
        }
        World w = Bukkit.getWorld(plugin.underworldName());
        if (w == null) {
            sender.sendMessage(msg("The Underworld is not loaded.", NamedTextColor.RED));
            return;
        }
        Terrain.Zone zone = null;
        if (args.length >= 2) {
            zone = switch (args[1].toLowerCase()) {
                case "hub" -> Terrain.Zone.BASE;
                case "crystal" -> Terrain.Zone.CRYSTAL;
                case "crack" -> Terrain.Zone.CRACK;
                case "snow" -> Terrain.Zone.SNOW;
                case "dark" -> Terrain.Zone.DARK;
                default -> null;
            };
        }
        if (zone == null) {
            sender.sendMessage(msg("Usage: /underworld biome <" + String.join("|", BIOMES) + "> [tp]",
                    NamedTextColor.GRAY));
            return;
        }
        int ox = p.getWorld().equals(w) ? p.getLocation().getBlockX() : 0;
        int oz = p.getWorld().equals(w) ? p.getLocation().getBlockZ() : 0;
        Terrain t = Terrain.get(w.getSeed());
        int[] pt = t.findZone(ox, oz, zone, 9000);
        if (pt == null) {
            sender.sendMessage(msg("That biome was not found within 9000 blocks.", NamedTextColor.RED));
            return;
        }
        long dist = Math.round(Math.hypot(pt[0] - ox, pt[1] - oz));
        sender.sendMessage(msg("Nearest " + args[1].toLowerCase() + " biome: " + pt[0] + ", " + pt[1]
                + "  (about " + dist + " blocks away)", NamedTextColor.GREEN));
        if (args.length >= 3 && args[2].equalsIgnoreCase("tp") && p.hasPermission("underworld.admin")) {
            int top = t.topY(pt[0], pt[1]);
            int y = top == Terrain.VOID ? 100 : top + 2;
            p.teleportAsync(new Location(w, pt[0] + 0.5, y, pt[1] + 0.5));
        }
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : SUBS) if (s.startsWith(args[0].toLowerCase())) out.add(s);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("locate")) {
            for (String s : STRUCTURES) if (s.startsWith(args[1].toLowerCase())) out.add(s);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("biome")) {
            for (String s : BIOMES) if (s.startsWith(args[1].toLowerCase())) out.add(s);
        } else if (args.length == 3 && sender.hasPermission("underworld.admin")) {
            out.add("tp");
        }
        return out;
    }
}
