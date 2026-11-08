package dev.underworld;

/** Only touched when Citizens is installed, so the plugin still loads without it. */
final class CitizensHook {

    private CitizensHook() { }

    static Runnable start(UnderworldPlugin plugin) {
        CitizensGuards guards = new CitizensGuards(plugin);
        plugin.getServer().getPluginManager().registerEvents(guards, plugin);
        guards.runTaskTimer(plugin, 100L, 10L);
        return guards::shutdown;
    }
}
