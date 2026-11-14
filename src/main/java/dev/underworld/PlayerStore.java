package dev.underworld;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Per-player Underworld data (coins, rank xp, quests, discoveries), one YAML file per player. */
public final class PlayerStore {

    public static final class Data {
        public long coins;
        public int xp;
        public int chests;
        public final Set<Integer> completed = new LinkedHashSet<>();
        public final Set<Integer> notified = new HashSet<>();
        public final Map<Integer, int[]> active = new LinkedHashMap<>();
        public final Set<String> zones = new LinkedHashSet<>();
        public final Set<String> discovered = new LinkedHashSet<>();
        /** "x,y,z" of activated waystones */
        public final Set<String> waystones = new LinkedHashSet<>();
        public final Set<Integer> lore = new LinkedHashSet<>();
    }

    private final File dir;
    private final Map<UUID, Data> cache = new HashMap<>();

    public PlayerStore(File dataFolder) {
        this.dir = new File(dataFolder, "players");
        if (!dir.exists()) dir.mkdirs();
    }

    private File file(UUID id) {
        return new File(dir, id + ".yml");
    }

    public synchronized Data get(UUID id) {
        Data d = cache.get(id);
        if (d == null) {
            d = load(id);
            cache.put(id, d);
        }
        return d;
    }

    private Data load(UUID id) {
        Data d = new Data();
        File f = file(id);
        if (!f.exists()) return d;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        d.coins = y.getLong("coins");
        d.xp = y.getInt("xp");
        d.chests = y.getInt("chests");
        d.completed.addAll(y.getIntegerList("completed"));
        d.notified.addAll(y.getIntegerList("notified"));
        d.zones.addAll(y.getStringList("zones"));
        d.discovered.addAll(y.getStringList("discovered"));
        d.waystones.addAll(y.getStringList("waystones"));
        d.lore.addAll(y.getIntegerList("lore"));
        ConfigurationSection a = y.getConfigurationSection("active");
        if (a != null) {
            for (String k : a.getKeys(false)) {
                List<Integer> l = a.getIntegerList(k);
                int[] arr = new int[l.size()];
                for (int i = 0; i < arr.length; i++) arr[i] = l.get(i);
                try {
                    d.active.put(Integer.parseInt(k), arr);
                } catch (NumberFormatException ignored) {
                    // skip broken entry
                }
            }
        }
        return d;
    }

    public synchronized void save(UUID id) {
        Data d = cache.get(id);
        if (d == null) return;
        YamlConfiguration y = new YamlConfiguration();
        y.set("coins", d.coins);
        y.set("xp", d.xp);
        y.set("chests", d.chests);
        y.set("completed", new java.util.ArrayList<>(d.completed));
        y.set("notified", new java.util.ArrayList<>(d.notified));
        y.set("zones", new java.util.ArrayList<>(d.zones));
        y.set("discovered", new java.util.ArrayList<>(d.discovered));
        y.set("waystones", new java.util.ArrayList<>(d.waystones));
        y.set("lore", new java.util.ArrayList<>(d.lore));
        for (Map.Entry<Integer, int[]> e : d.active.entrySet()) {
            java.util.List<Integer> l = new java.util.ArrayList<>();
            for (int v : e.getValue()) l.add(v);
            y.set("active." + e.getKey(), l);
        }
        try {
            y.save(file(id));
        } catch (IOException ex) {
            System.err.println("[Underworld] Could not save player data " + id + ": " + ex.getMessage());
        }
    }

    public synchronized void saveAll() {
        for (UUID id : new java.util.ArrayList<>(cache.keySet())) save(id);
    }

    public synchronized void unload(UUID id) {
        save(id);
        cache.remove(id);
    }
}
