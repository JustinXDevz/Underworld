package dev.underworld;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.persistence.PersistentDataType;

/**
 * The netherite knights: full enchanted netherite with green (emerald) trims.
 * kind 1 = outpost knight, kind 2 = wandering guard.
 */
public final class Knights {

    private final NamespacedKey key;

    public Knights(UnderworldPlugin plugin) {
        this.key = new NamespacedKey(plugin, "knight");
    }

    /** 0 = not ours, 1 = outpost knight, 2 = wandering guard. */
    public int kind(Entity e) {
        Byte b = e.getPersistentDataContainer().get(key, PersistentDataType.BYTE);
        return b == null ? 0 : b;
    }

    public WitherSkeleton spawn(Location loc, boolean outpost) {
        return loc.getWorld().spawn(loc, WitherSkeleton.class, ws -> equip(ws, outpost));
    }

    private void equip(WitherSkeleton ws, boolean outpost) {
        ws.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) (outpost ? 1 : 2));
        ws.customName(Component.text(outpost ? "Outpost Knight" : "Underworld Guard", NamedTextColor.GREEN));
        ws.setCustomNameVisible(true);

        EntityEquipment eq = ws.getEquipment();
        eq.setHelmet(armor(Material.NETHERITE_HELMET));
        eq.setChestplate(armor(Material.NETHERITE_CHESTPLATE));
        eq.setLeggings(armor(Material.NETHERITE_LEGGINGS));
        eq.setBoots(armor(Material.NETHERITE_BOOTS));

        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 5);
        sword.addUnsafeEnchantment(Enchantment.FIRE_ASPECT, 2);
        sword.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
        eq.setItemInMainHand(sword);

        float armorDrop = outpost ? 0.30f : 0.12f;
        float swordDrop = outpost ? 0.45f : 0.15f;
        eq.setHelmetDropChance(armorDrop);
        eq.setChestplateDropChance(armorDrop);
        eq.setLeggingsDropChance(armorDrop);
        eq.setBootsDropChance(armorDrop);
        eq.setItemInMainHandDropChance(swordDrop);

        AttributeInstance health = ws.getAttribute(Attribute.MAX_HEALTH);
        if (health != null) {
            health.setBaseValue(outpost ? 100 : 70);
            ws.setHealth(health.getBaseValue());
        }
        AttributeInstance follow = ws.getAttribute(Attribute.FOLLOW_RANGE);
        if (follow != null) follow.setBaseValue(48);
        AttributeInstance speed = ws.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(0.3);

        ws.setRemoveWhenFarAway(false);
        ws.setPersistent(true);
        ws.setCanPickupItems(false);
    }

    private ItemStack armor(Material material) {
        ItemStack item = new ItemStack(material);
        item.addUnsafeEnchantment(Enchantment.PROTECTION, 4);
        item.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
        if (item.getItemMeta() instanceof ArmorMeta meta) {
            meta.setTrim(new ArmorTrim(TrimMaterial.EMERALD, TrimPattern.SILENCE));
            item.setItemMeta(meta);
        }
        return item;
    }
}
