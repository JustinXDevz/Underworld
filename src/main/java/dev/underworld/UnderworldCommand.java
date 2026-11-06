package dev.underworld;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class UnderworldCommand implements CommandExecutor {

    private final UnderworldPlugin plugin;

    public UnderworldCommand(UnderworldPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "info" : args[0].toLowerCase();
        switch (sub) {
            case "reload" -> {
                plugin.reload();
                sender.sendMessage(Component.text("Underworld config reloaded.", NamedTextColor.GREEN));
            }
            case "tp" -> {
                if (sender instanceof Player p) {
                    plugin.transition().sendDown(p);
                } else {
                    sender.sendMessage("Players only.");
                }
            }
            default -> {
                World w = Bukkit.getWorld(plugin.underworldName());
                sender.sendMessage(Component.text("Underworld world: " + plugin.underworldName()
                        + (w == null ? " (not loaded)" : " (loaded)")
                        + " | Nether world: " + plugin.netherName(), NamedTextColor.GOLD));
                sender.sendMessage(Component.text("Usage: /underworld <tp|info|reload>", NamedTextColor.GRAY));
            }
        }
        return true;
    }
}
