package ru.mirtime.duels;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

import java.util.List;

public class DuelModes {

    public record ModeInfo(String id, String title, Material icon, List<String> desc, Material floorMain, Material floorAccent, Material wallMat) {}

    public static final List<ModeInfo> MODES = List.of(
            new ModeInfo("mace", "&#9400d3&l🌀 Булава и Ветер", Material.MACE,
                    List.of("&7Алмазная броня Защита IV,", "&7Булава Плотность IV, Заряды ветра", "&7и Элитры для воздушных атак!"),
                    Material.PURPUR_BLOCK, Material.END_STONE_BRICKS, Material.PURPLE_STAINED_GLASS),
            new ModeInfo("anarchy", "&#FFD700&l⚔ Хардкор Анархия", Material.NETHERITE_SWORD,
                    List.of("&7Незеритовый сет Защита IV, Меч,", "&7Булава, 3 Чарки, Тотемы и баффы!"),
                    Material.DEEPSLATE_BRICKS, Material.CRYING_OBSIDIAN, Material.BLUE_STAINED_GLASS),
            new ModeInfo("crystal", "&c&l💎 Кристалл PvP", Material.END_CRYSTAL,
                    List.of("&7Незеритовый сет, Обсидиан,", "&7Кристаллы Энда и Якоря возрождения!"),
                    Material.OBSIDIAN, Material.BLACKSTONE, Material.RED_STAINED_GLASS),
            new ModeInfo("classic", "&#00E5FF&l🛡 Классика 1.21", Material.DIAMOND_SWORD,
                    List.of("&7Алмазный сет, Меч Острота V,", "&7Щит, Лук и 16 золотых яблок."),
                    Material.STONE_BRICKS, Material.POLISHED_ANDESITE, Material.LIGHT_BLUE_STAINED_GLASS),
            new ModeInfo("uhc", "&6&l🔥 UHC Дуэль", Material.GOLDEN_APPLE,
                    List.of("&7Алмазный сет, Вёдра лавы и воды,", "&7Лук и яблоки без авто-регенерации!"),
                    Material.GRASS_BLOCK, Material.MOSSY_COBBLESTONE, Material.GREEN_STAINED_GLASS),
            new ModeInfo("axe", "&e&l🪓 Топоры и Щиты", Material.NETHERITE_AXE,
                    List.of("&7Незеритовый топор, Щит, Арбалет", "&7и алмазно-железная броня."),
                    Material.SPRUCE_PLANKS, Material.STRIPPED_DARK_OAK_LOG, Material.YELLOW_STAINED_GLASS),
            new ModeInfo("sumo", "&#00FF66&l🥋 Сумо на Платформе", Material.STICK,
                    List.of("&7Без брони! Только Палка Отдача II.", "&7Скинь противника с арены первым!"),
                    Material.QUARTZ_BLOCK, Material.SEA_LANTERN, Material.AIR),
            new ModeInfo("archer", "&a&l🏹 Дуэль Снайперов", Material.BOW,
                    List.of("&7Кожаная броня, Лук на Силу V,", "&7Бесконечность и Заряды ветра!"),
                    Material.SANDSTONE, Material.SMOOTH_SANDSTONE, Material.LIME_STAINED_GLASS),
            new ModeInfo("potions", "&d&l🧪 Незерит Зелья", Material.SPLASH_POTION,
                    List.of("&7Незеритовый сет Острота V,", "&7Зелья лечения II, Силы и Скорости!"),
                    Material.NETHER_BRICKS, Material.RED_NETHER_BRICKS, Material.MAGENTA_STAINED_GLASS)
    );

    public static ModeInfo getMode(String id) {
        return MODES.stream().filter(m -> m.id().equalsIgnoreCase(id)).findFirst().orElse(MODES.get(0));
    }
    public static void equip(Player p, String modeId) {
        p.getInventory().clear();
        p.getInventory().setArmorContents(null);
        p.getActivePotionEffects().forEach(ef -> p.removePotionEffect(ef.getType()));
        p.setHealth(20.0);
        p.setFoodLevel(20);
        p.setFireTicks(0);

        switch (modeId.toLowerCase()) {
            case "mace" -> {
                setArmor(p, Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS);
                p.getInventory().setItem(0, ench(Material.MACE, Enchantment.DENSITY, 4, Enchantment.BREACH, 2));
                p.getInventory().setItem(1, ench(Material.DIAMOND_SWORD, Enchantment.SHARPNESS, 5, Enchantment.UNBREAKING, 3));
                p.getInventory().setItem(2, new ItemStack(Material.WIND_CHARGE, 64));
                p.getInventory().setItem(3, new ItemStack(Material.GOLDEN_APPLE, 16));
                p.getInventory().setItem(4, new ItemStack(Material.ELYTRA));
                p.getInventory().setItemInOffHand(new ItemStack(Material.TOTEM_OF_UNDYING));
            }
            case "anarchy" -> {
                setArmor(p, Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS);
                p.getInventory().setItem(0, ench(Material.NETHERITE_SWORD, Enchantment.SHARPNESS, 5, Enchantment.FIRE_ASPECT, 2));
                p.getInventory().setItem(1, ench(Material.MACE, Enchantment.DENSITY, 4, Enchantment.BREACH, 2));
                p.getInventory().setItem(2, new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 3));
                p.getInventory().setItem(3, new ItemStack(Material.GOLDEN_APPLE, 24));
                p.getInventory().setItem(4, new ItemStack(Material.ENDER_PEARL, 16));
                p.getInventory().setItem(5, new ItemStack(Material.WIND_CHARGE, 32));
                p.getInventory().setItem(6, splash(PotionType.STRONG_HEALING, 1));
                p.getInventory().setItem(7, splash(PotionType.STRONG_STRENGTH, 1));
                p.getInventory().setItem(8, new ItemStack(Material.TOTEM_OF_UNDYING, 2));
                p.getInventory().setItemInOffHand(new ItemStack(Material.TOTEM_OF_UNDYING));
            }
            case "crystal" -> {
                setArmor(p, Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS);
                p.getInventory().setItem(0, ench(Material.NETHERITE_SWORD, Enchantment.SHARPNESS, 5, Enchantment.KNOCKBACK, 1));
                p.getInventory().setItem(1, new ItemStack(Material.OBSIDIAN, 64));
                p.getInventory().setItem(2, new ItemStack(Material.END_CRYSTAL, 64));
                p.getInventory().setItem(3, new ItemStack(Material.RESPAWN_ANCHOR, 16));
                p.getInventory().setItem(4, new ItemStack(Material.GLOWSTONE, 64));
                p.getInventory().setItem(5, new ItemStack(Material.ENDER_PEARL, 16));
                p.getInventory().setItem(6, new ItemStack(Material.GOLDEN_APPLE, 32));
                p.getInventory().setItem(7, new ItemStack(Material.TOTEM_OF_UNDYING, 3));
                p.getInventory().setItemInOffHand(new ItemStack(Material.TOTEM_OF_UNDYING));
            }
            case "uhc" -> {
                setArmor(p, Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS);
                p.getInventory().setItem(0, ench(Material.DIAMOND_SWORD, Enchantment.SHARPNESS, 4, Enchantment.UNBREAKING, 3));
                p.getInventory().setItem(1, ench(Material.BOW, Enchantment.POWER, 3, Enchantment.UNBREAKING, 3));
                p.getInventory().setItem(2, new ItemStack(Material.GOLDEN_APPLE, 12));
                p.getInventory().setItem(3, new ItemStack(Material.LAVA_BUCKET));
                p.getInventory().setItem(4, new ItemStack(Material.WATER_BUCKET));
                p.getInventory().setItem(5, new ItemStack(Material.COBBLESTONE, 64));
                p.getInventory().setItem(8, new ItemStack(Material.ARROW, 32));
                p.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
            }
            case "axe" -> {
                setArmor(p, Material.DIAMOND_HELMET, Material.IRON_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.IRON_BOOTS);
                p.getInventory().setItem(0, ench(Material.NETHERITE_AXE, Enchantment.SHARPNESS, 4, Enchantment.UNBREAKING, 3));
                p.getInventory().setItem(1, ench(Material.CROSSBOW, Enchantment.QUICK_CHARGE, 3, Enchantment.MULTISHOT, 1));
                p.getInventory().setItem(2, new ItemStack(Material.GOLDEN_APPLE, 8));
                p.getInventory().setItem(8, new ItemStack(Material.ARROW, 32));
                p.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
            }
            case "sumo" -> {
                ItemStack stick = new ItemStack(Material.STICK);
                stick.addUnsafeEnchantment(Enchantment.KNOCKBACK, 2);
                p.getInventory().setItem(0, stick);
                p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 99999, 4, false, false));
            }
            case "archer" -> {
                setArmor(p, Material.LEATHER_HELMET, Material.LEATHER_CHESTPLATE, Material.LEATHER_LEGGINGS, Material.LEATHER_BOOTS);
                p.getInventory().setItem(0, ench(Material.BOW, Enchantment.POWER, 5, Enchantment.INFINITY, 1));
                p.getInventory().setItem(1, new ItemStack(Material.WIND_CHARGE, 32));
                p.getInventory().setItem(2, new ItemStack(Material.GOLDEN_APPLE, 8));
                p.getInventory().setItem(9, new ItemStack(Material.ARROW, 1));
            }
            case "potions" -> {
                setArmor(p, Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS);
                p.getInventory().setItem(0, ench(Material.NETHERITE_SWORD, Enchantment.SHARPNESS, 5, Enchantment.UNBREAKING, 3));
                p.getInventory().setItem(1, new ItemStack(Material.ENDER_PEARL, 16));
                p.getInventory().setItem(2, new ItemStack(Material.GOLDEN_CARROT, 32));
                p.getInventory().setItem(3, splash(PotionType.STRONG_SWIFTNESS, 1));
                p.getInventory().setItem(4, splash(PotionType.STRONG_STRENGTH, 1));
                for (int i = 5; i < 36; i++) p.getInventory().setItem(i, splash(PotionType.STRONG_HEALING, 1));
            }
            default -> {
                setArmor(p, Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS);
                p.getInventory().setItem(0, ench(Material.DIAMOND_SWORD, Enchantment.SHARPNESS, 5, Enchantment.FIRE_ASPECT, 2));
                p.getInventory().setItem(1, ench(Material.BOW, Enchantment.POWER, 4, Enchantment.INFINITY, 1));
                p.getInventory().setItem(2, new ItemStack(Material.GOLDEN_APPLE, 16));
                p.getInventory().setItem(3, new ItemStack(Material.ENDER_PEARL, 8));
                p.getInventory().setItem(9, new ItemStack(Material.ARROW, 1));
                p.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
            }
        }
        p.updateInventory();
    }

    // Автоматический генератор красивой индивидуальной арены под выбранный режим!
    public static void buildArenaStructure(Location center, String modeId) {
        ModeInfo info = getMode(modeId);
        World w = center.getWorld();
        int r = modeId.equalsIgnoreCase("sumo") ? 8 : 16;
        int height = modeId.equalsIgnoreCase("sumo") ? 0 : 14;

        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                double dist = Math.sqrt(x * x + z * z);
                if (dist > r + 0.5) continue;

                // Пол с узором
                Material floor = ((x + z) % 4 == 0 || dist > r - 1.5) ? info.floorAccent() : info.floorMain();
                w.getBlockAt(center.getBlockX() + x, center.getBlockY() - 1, center.getBlockZ() + z).setType(floor);

                // Очистка воздуха внутри и постройка стеклянного купола
                for (int y = 0; y <= height; y++) {
                    if (info.wallMat() != Material.AIR && dist >= r - 0.8) {
                        w.getBlockAt(center.getBlockX() + x, center.getBlockY() + y, center.getBlockZ() + z).setType(info.wallMat());
                    } else {
                        w.getBlockAt(center.getBlockX() + x, center.getBlockY() + y, center.getBlockZ() + z).setType(Material.AIR);
                    }
                }
            }
        }
    }

    private static void setArmor(Player p, Material h, Material c, Material l, Material b) {
        p.getInventory().setHelmet(ench(h, Enchantment.PROTECTION, 4, Enchantment.UNBREAKING, 3));
        p.getInventory().setChestplate(ench(c, Enchantment.PROTECTION, 4, Enchantment.UNBREAKING, 3));
        p.getInventory().setLeggings(ench(l, Enchantment.PROTECTION, 4, Enchantment.UNBREAKING, 3));
        p.getInventory().setBoots(ench(b, Enchantment.PROTECTION, 4, Enchantment.UNBREAKING, 3));
    }

    private static ItemStack ench(Material mat, Enchantment e1, int l1, Enchantment e2, int l2) {
        ItemStack is = new ItemStack(mat);
        is.addUnsafeEnchantment(e1, l1);
        is.addUnsafeEnchantment(e2, l2);
        return is;
    }

    private static ItemStack splash(PotionType type, int amt) {
        ItemStack is = new ItemStack(Material.SPLASH_POTION, amt);
        if (is.getItemMeta() instanceof PotionMeta pm) {
            pm.setBasePotionType(type);
            is.setItemMeta(pm);
        }
        return is;
    }
}
