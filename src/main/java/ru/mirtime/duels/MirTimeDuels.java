package ru.mirtime.duels;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MirTimeDuels extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    private record DuelRequest(UUID sender, UUID target, String mode, int bet) {}
    private record SavedState(Location loc, ItemStack[] inv, ItemStack[] armor, ItemStack offhand) {}

    private final Map<UUID, DuelRequest> pendingSetup = new ConcurrentHashMap<>();
    private final Map<UUID, DuelRequest> incomingRequests = new ConcurrentHashMap<>();
    private final Map<String, UUID> modeQueues = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> activeDuels = new ConcurrentHashMap<>();
    private final Map<UUID, String> activeMode = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> activeBets = new ConcurrentHashMap<>();
    private final Map<UUID, SavedState> savedStates = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (getCommand("duel") != null) {
            getCommand("duel").setExecutor(this);
            getCommand("duel").setTabCompleter(this);
        }
        if (getCommand("mtduels") != null) {
            getCommand("mtduels").setExecutor(this);
            getCommand("mtduels").setTabCompleter(this);
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("MirTimeDuels v2.0.0 (9 режимов и индивидуальные арены) успешно запущен!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String prefix = color(getConfig().getString("prefix", "&#0055ff&l⚡ &#00E5FF&lMIR-TIME &8» "));
        List<String> list = List.of(args);

        if (cmd.getName().equalsIgnoreCase("mtduels")) {
            if (!(sender instanceof Player p)) return true;
            if (list.size() < 2) {
                p.sendMessage(prefix + color("&fКоманды админа:"));
                p.sendMessage(color(" &#00FF66/mtduels buildarena <режим> &7— авто-постройка арены и спавнов!"));
                p.sendMessage(color(" &#00FF66/mtduels setpos1 <режим> &7/ &#00FF66setpos2 <режим> &7— точки вручную"));
                return true;
            }
            String sub = list.get(0).toLowerCase();
            String modeId = list.get(1).toLowerCase();
            DuelModes.ModeInfo info = DuelModes.getMode(modeId);

            if (sub.equals("buildarena")) {
                Location center = p.getLocation().getBlock().getLocation();
                DuelModes.buildArenaStructure(center, info.id());
                int offset = info.id().equals("sumo") ? 5 : 11;

                Location p1 = center.clone().add(0.5, 0, -offset);
                p1.setYaw(0f); p1.setPitch(0f);
                Location p2 = center.clone().add(0.5, 0, offset);
                p2.setYaw(180f); p2.setPitch(0f);

                saveArenaLoc(info.id(), "pos1", p1);
                saveArenaLoc(info.id(), "pos2", p2);
                p.teleport(center.clone().add(0.5, 2, 0.5));
                p.sendMessage(prefix + color("&#00FF66Красивая арена для режима " + info.title() + " &#00FF66построена, точки спавна установлены!"));
                return true;
            }
            if (sub.equals("setpos1") || sub.equals("setpos2")) {
                saveArenaLoc(info.id(), sub.equals("setpos1") ? "pos1" : "pos2", p.getLocation());
                p.sendMessage(prefix + color("&#00FF66Точка &f" + sub + " &#00FF66для режима " + info.title() + " &#00FF66сохранена!"));
            }
            return true;
        }

        if (!(sender instanceof Player p)) return true;
        if (activeDuels.containsKey(p.getUniqueId())) {
            p.sendMessage(prefix + color("&cВы уже находитесь в бою!"));
            return true;
        }

        if (list.isEmpty()) {
            pendingSetup.remove(p.getUniqueId());
            openModeMenu(p, null, 0);
            return true;
        }

        if (list.get(0).equalsIgnoreCase("accept")) {
            DuelRequest req = incomingRequests.remove(p.getUniqueId());
            if (req == null) {
                p.sendMessage(prefix + color("&cУ вас нет активных вызовов на дуэль."));
                return true;
            }
            Player challenger = Bukkit.getPlayer(req.sender());
            if (challenger == null || !challenger.isOnline()) {
                p.sendMessage(prefix + color("&cПротивник вышел с сервера."));
                return true;
            }
            startDuel(challenger, p, req.mode(), req.bet());
            return true;
        }

        if (list.get(0).equalsIgnoreCase("leave")) {
            modeQueues.values().remove(p.getUniqueId());
            p.sendMessage(prefix + color("&eВы вышли из очереди поиска дуэли."));
            return true;
        }

        Player target = Bukkit.getPlayer(list.get(0));
        if (target == null || target.equals(p)) {
            p.sendMessage(prefix + color("&cИгрок не в сети или указан неверный ник! Введите просто &#00FF66/duel &cдля поиска случайного соперника."));
            return true;
        }
        if (activeDuels.containsKey(target.getUniqueId())) {
            p.sendMessage(prefix + color("&cЭтот игрок уже сражается на арене!"));
            return true;
        }

        int bet = 0;
        if (list.size() >= 2) {
            try { bet = Math.max(0, Integer.parseInt(list.get(1))); } catch (NumberFormatException e) {
                p.sendMessage(prefix + color("&cСтавка должна быть целым числом Иксов!"));
                return true;
            }
        }
        if (bet > 0 && getIksyBalance(p) < bet) {
            p.sendMessage(prefix + color("&cУ вас недостаточно Иксов для ставки &#00E5FF" + bet + " 🅧&c!"));
            return true;
        }

        pendingSetup.put(p.getUniqueId(), new DuelRequest(p.getUniqueId(), target.getUniqueId(), "mace", bet));
        openModeMenu(p, target.getName(), bet);
        return true;
    }
    private void openModeMenu(Player p, String targetName, int bet) {
        String title = targetName != null
                ? color("&#0055ff&l⚔ Вызов: &#00FF66" + targetName)
                : color("&#0055ff&l⚡ &#00E5FF&lВЫБОР РЕЖИМА ДУЭЛИ");
        Inventory inv = Bukkit.createInventory(null, 45, title);

        ItemStack blue = item(Material.BLUE_STAINED_GLASS_PANE, "&#0055ff▪", null);
        ItemStack cyan = item(Material.LIGHT_BLUE_STAINED_GLASS_PANE, "&#00E5FF▫", null);
        for (int s : new int[]{0, 8, 36, 44}) inv.setItem(s, blue);
        for (int s : new int[]{1, 2, 3, 5, 6, 7, 9, 17, 18, 26, 27, 35, 37, 38, 39, 41, 42, 43}) inv.setItem(s, cyan);

        inv.setItem(4, item(Material.NETHER_STAR, "&#00E5FF&l✦ ДУЭЛЬНЫЙ КЛУБ MIR-TIME ✦", List.of(
                "&7Выберите любой из &#00FF669 PvP-режимов&7!",
                "&7Оба игрока получают абсолютно одинаковый",
                "&7сбалансированный набор экипировки.",
                targetName != null ? (bet > 0 ? "&fСтавка на бой: &#00E5FF&l" + bet + " Иксов 🅧" : "&7Дружеский бой без ставки") : "&#00FF66▶ Нажмите на режим для быстрого поиска!"
        )));

        int[] slots = {11, 12, 13, 14, 15, 21, 22, 23, 31};
        for (int i = 0; i < DuelModes.MODES.size(); i++) {
            DuelModes.ModeInfo m = DuelModes.MODES.get(i);
            List<String> lore = new ArrayList<>(m.desc());
            lore.add("");
            boolean hasArena = getConfig().contains("arenas." + m.id() + ".pos1");
            lore.add(hasArena ? "&#00FF66✔ Индивидуальная арена готова" : "&e⚠ Арена по умолчанию");
            if (targetName == null) {
                UUID inQ = modeQueues.get(m.id());
                lore.add(inQ != null ? "&#00E5FF⚡ В очереди: 1 игрок (Нажмите для боя!)" : "&7В очереди: 0 игроков");
            }
            lore.add("&#00FF66▶ Нажмите, чтобы выбрать режим");
            inv.setItem(slots[i], item(m.icon(), m.title(), lore));
        }

        inv.setItem(40, item(Material.BARRIER, "&c&l✖ Выйти из очереди / Закрыть", List.of("&7Нажмите, чтобы отменить поиск.")));
        p.openInventory(inv);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String title = e.getView().getTitle();
        if (!title.contains("Вызов:") && !title.contains("ВЫБОР РЕЖИМА ДУЭЛИ")) return;
        e.setCancelled(true);

        String prefix = color(getConfig().getString("prefix", "&#0055ff&l⚡ &#00E5FF&lMIR-TIME &8» "));
        int slot = e.getRawSlot();
        if (slot == 40) {
            modeQueues.values().remove(p.getUniqueId());
            p.closeInventory();
            return;
        }

        int[] slots = {11, 12, 13, 14, 15, 21, 22, 23, 31};
        String chosenMode = null;
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == slot) { chosenMode = DuelModes.MODES.get(i).id(); break; }
        }
        if (chosenMode == null) return;

        p.closeInventory();
        DuelModes.ModeInfo modeInfo = DuelModes.getMode(chosenMode);
        DuelRequest setup = pendingSetup.remove(p.getUniqueId());

        if (setup != null) {
            Player target = Bukkit.getPlayer(setup.target());
            if (target == null) return;

            DuelRequest finalReq = new DuelRequest(p.getUniqueId(), target.getUniqueId(), chosenMode, setup.bet());
            incomingRequests.put(target.getUniqueId(), finalReq);

            p.sendMessage(prefix + color("&#00FF66Вызов отправлен игроку &f" + target.getName() + " &7(" + modeInfo.title() + "&7)!"));
            target.sendMessage(color("&#0055ff&l&m                                                  "));
            target.sendMessage(color(" &#0055ff&l⚔ &#00E5FF&lВЫЗОВ НА ДУЭЛЬ &8| &fОт: &#00FF66" + p.getName()));
            target.sendMessage(color(" &#0055ff● &fРежим: " + modeInfo.title() + (setup.bet() > 0 ? " &8| &fСтавка: &#00E5FF&l" + setup.bet() + " Иксов 🅧" : " &8| &7Без ставки")));
            target.sendMessage(Component.text(color(" &#00FF66&l[✔ ПРИНЯТЬ ВЫЗОВ — НАЖМИТЕ СЮДА]")).clickEvent(ClickEvent.runCommand("/duel accept")));
            target.sendMessage(color("&#0055ff&l&m                                                  "));
            target.playSound(target.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
            return;
        }

        modeQueues.values().remove(p.getUniqueId());
        UUID waitingId = modeQueues.get(chosenMode);
        if (waitingId != null) {
            Player opponent = Bukkit.getPlayer(waitingId);
            modeQueues.remove(chosenMode);
            if (opponent != null && opponent.isOnline() && !opponent.equals(p)) {
                startDuel(opponent, p, chosenMode, 0);
                return;
            }
        }
        modeQueues.put(chosenMode, p.getUniqueId());
        p.sendMessage(prefix + color("&#00FF66Вы встали в очередь режима " + modeInfo.title() + "&#00FF66! Ожидаем соперника... &7(/duel leave для выхода)"));
    }

    private void startDuel(Player p1, Player p2, String modeId, int bet) {
        String prefix = color(getConfig().getString("prefix", "&#0055ff&l⚡ &#00E5FF&lMIR-TIME &8» "));
        if (bet > 0) {
            if (getIksyBalance(p1) < bet || getIksyBalance(p2) < bet) {
                p1.sendMessage(prefix + color("&cУ одного из игроков не хватает Иксов для ставки!"));
                p2.sendMessage(prefix + color("&cУ одного из игроков не хватает Иксов для ставки!"));
                return;
            }
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "giks take " + p1.getName() + " " + bet + " Iksy");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "giks take " + p2.getName() + " " + bet + " Iksy");
        }

        modeQueues.values().remove(p1.getUniqueId());
        modeQueues.values().remove(p2.getUniqueId());

        saveState(p1);
        saveState(p2);

        activeDuels.put(p1.getUniqueId(), p2.getUniqueId());
        activeDuels.put(p2.getUniqueId(), p1.getUniqueId());
        activeMode.put(p1.getUniqueId(), modeId);
        activeMode.put(p2.getUniqueId(), modeId);
        activeBets.put(p1.getUniqueId(), bet);
        activeBets.put(p2.getUniqueId(), bet);

        p1.setGameMode(GameMode.SURVIVAL);
        p2.setGameMode(GameMode.SURVIVAL);
        DuelModes.equip(p1, modeId);
        DuelModes.equip(p2, modeId);

        p1.teleport(getArenaLoc(modeId, "pos1"));
        p2.teleport(getArenaLoc(modeId, "pos2"));

        DuelModes.ModeInfo info = DuelModes.getMode(modeId);
        p1.sendTitle(color(info.title()), color("&fПротивник: &#00E5FF" + p2.getName()), 5, 45, 10);
        p2.sendTitle(color(info.title()), color("&fПротивник: &#00E5FF" + p1.getName()), 5, 45, 10);
        p1.playSound(p1.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.8f, 1.2f);
        p2.playSound(p2.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.8f, 1.2f);
    }
    private void finishDuel(Player winner, Player loser) {
        UUID wId = winner.getUniqueId();
        UUID lId = loser.getUniqueId();
        String modeId = activeMode.getOrDefault(wId, "classic");

        activeDuels.remove(wId);
        activeDuels.remove(lId);
        activeMode.remove(wId);
        activeMode.remove(lId);
        int bet = activeBets.getOrDefault(wId, 0);
        activeBets.remove(wId);
        activeBets.remove(lId);

        restoreState(winner);
        if (loser.isOnline()) {
            Bukkit.getScheduler().runTaskLater(this, () -> restoreState(loser), 2L);
        }

        if ((modeId.equals("crystal") || modeId.equals("uhc")) && getConfig().contains("arenas." + modeId + ".pos1")) {
            Location l1 = getArenaLoc(modeId, "pos1");
            Location l2 = getArenaLoc(modeId, "pos2");
            Location center = l1.clone().add(l2).multiply(0.5);
            center.setY(l1.getY());
            Bukkit.getScheduler().runTaskLater(this, () -> DuelModes.buildArenaStructure(center, modeId), 10L);
        }

        String prizeText = "";
        if (bet > 0) {
            int totalBank = bet * 2;
            int comm = getConfig().getInt("commission-percent", 5);
            int winAmount = Math.max(1, totalBank - (totalBank * comm / 100));
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "giks give " + winner.getName() + " " + winAmount + " Iksy");
            prizeText = " &8(&fВыигрыш: &#00E5FF&l" + winAmount + " Иксов 🅧&8)";
        }

        DuelModes.ModeInfo info = DuelModes.getMode(modeId);
        Bukkit.broadcastMessage(color("&#0055ff&l⚡ &#00E5FF&lMIR-TIME &8» &#00FF66" + winner.getName() + " &fпобедил &c" + loser.getName() + " &fв дуэли " + info.title() + "!" + prizeText));
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (!"sumo".equals(activeMode.get(p.getUniqueId()))) return;
        Location arenaPos = getArenaLoc("sumo", "pos1");
        if (p.getLocation().getY() < arenaPos.getY() - 3.0) {
            UUID oppId = activeDuels.get(p.getUniqueId());
            Player winner = oppId != null ? Bukkit.getPlayer(oppId) : null;
            if (winner != null) finishDuel(winner, p);
        }
    }

    @EventHandler
    public void onRegen(EntityRegainHealthEvent e) {
        if (e.getEntity() instanceof Player p && "uhc".equals(activeMode.get(p.getUniqueId()))) {
            if (e.getRegainReason() == EntityRegainHealthEvent.RegainReason.SATIATED || e.getRegainReason() == EntityRegainHealthEvent.RegainReason.REGEN) {
                e.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player dead = e.getEntity();
        UUID oppId = activeDuels.get(dead.getUniqueId());
        if (oppId == null) return;

        e.getDrops().clear();
        e.setDroppedExp(0);
        e.setKeepInventory(true);

        Player winner = Bukkit.getPlayer(oppId);
        if (winner != null) finishDuel(winner, dead);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player left = e.getPlayer();
        modeQueues.values().remove(left.getUniqueId());
        UUID oppId = activeDuels.get(left.getUniqueId());
        if (oppId == null) return;

        restoreState(left);
        Player winner = Bukkit.getPlayer(oppId);
        if (winner != null) finishDuel(winner, left);
    }

    @EventHandler
    public void onCmd(PlayerCommandPreprocessEvent e) {
        if (activeDuels.containsKey(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(color(getConfig().getString("prefix") + "&cКоманды запрещены во время дуэли!"));
        }
    }

    private void saveState(Player p) {
        savedStates.put(p.getUniqueId(), new SavedState(
                p.getLocation().clone(),
                p.getInventory().getContents().clone(),
                p.getInventory().getArmorContents().clone(),
                p.getInventory().getItemInOffHand().clone()
        ));
    }

    private void restoreState(Player p) {
        SavedState st = savedStates.remove(p.getUniqueId());
        if (st == null) return;
        p.getActivePotionEffects().forEach(ef -> p.removePotionEffect(ef.getType()));
        p.getInventory().clear();
        p.getInventory().setContents(st.inv());
        p.getInventory().setArmorContents(st.armor());
        p.getInventory().setItemInOffHand(st.offhand());
        p.setHealth(20.0);
        p.setFoodLevel(20);
        p.teleport(st.loc());
        p.updateInventory();
    }

    private void saveArenaLoc(String modeId, String pos, Location l) {
        String path = "arenas." + modeId + "." + pos;
        getConfig().set(path + ".world", l.getWorld().getName());
        getConfig().set(path + ".x", l.getX());
        getConfig().set(path + ".y", l.getY());
        getConfig().set(path + ".z", l.getZ());
        getConfig().set(path + ".yaw", l.getYaw());
        getConfig().set(path + ".pitch", l.getPitch());
        saveConfig();
    }

    private Location getArenaLoc(String modeId, String pos) {
        String path = getConfig().contains("arenas." + modeId + "." + pos) ? "arenas." + modeId + "." + pos : "arenas.classic." + pos;
        World w = Bukkit.getWorld(getConfig().getString(path + ".world", "game"));
        if (w == null) w = Bukkit.getWorlds().get(0);
        return new Location(w,
                getConfig().getDouble(path + ".x", -200.5),
                getConfig().getDouble(path + ".y", 110.0),
                getConfig().getDouble(path + ".z", pos.equals("pos1") ? 90.0 : 110.0),
                (float) getConfig().getDouble(path + ".yaw", pos.equals("pos1") ? 0.0 : 180.0),
                (float) getConfig().getDouble(path + ".pitch", 0.0));
    }

    public double getIksyBalance(Player p) {
        try {
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
                Method m = papi.getMethod("setPlaceholders", Player.class, String.class);
                String res = (String) m.invoke(null, p, "%gemseconomy_balance_default%");
                return Double.parseDouble(res.replaceAll("[^0-9.]", ""));
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private ItemStack item(Material mat, String name, List<String> lore) {
        ItemStack is = new ItemStack(mat);
        ItemMeta meta = is.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            if (lore != null) {
                List<String> l = new ArrayList<>();
                for (String s : lore) l.add(color(s));
                meta.setLore(l);
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
            is.setItemMeta(meta);
        }
        return is;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (cmd.getName().equalsIgnoreCase("mtduels")) {
            if (args.length == 1) return List.of("buildarena", "setpos1", "setpos2");
            if (args.length == 2) return DuelModes.MODES.stream().map(DuelModes.ModeInfo::id).toList();
        }
        return null;
    }

    public static String color(String text) {
        if (text == null) return "";
        Matcher m = Pattern.compile("&#([A-Fa-f0-9]{6})").matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            StringBuilder rep = new StringBuilder("§x");
            for (char c : m.group(1).toCharArray()) rep.append('§').append(c);
            m.appendReplacement(sb, rep.toString());
        }
        m.appendTail(sb);
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }
}
