package ru.mirtime.duels;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
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
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MirTimeDuels extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    private record DuelSetup(UUID target, String mode) {}
    private record DuelRequest(UUID sender, UUID target, String mode, String betType, int betAmount) {}
    private record SavedState(Location loc, ItemStack[] inv, ItemStack[] armor, ItemStack offhand) {}

    private final Map<UUID, DuelSetup> setupStep = new ConcurrentHashMap<>();
    private final Map<UUID, DuelRequest> incomingRequests = new ConcurrentHashMap<>();
    private final Map<String, UUID> modeQueues = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> activeDuels = new ConcurrentHashMap<>();
    private final Map<UUID, String> activeMode = new ConcurrentHashMap<>();
    private final Map<UUID, DuelRequest> activeMeta = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> activeBars = new ConcurrentHashMap<>();
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
        getLogger().info("MirTimeDuels v2.5.0 (BossBar, 9 режимов, ставки на Иксы/Токены/Монеты/Лут) запущен!");
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
            DuelModes.ModeInfo info = DuelModes.getMode(list.get(1));

            if (sub.equals("buildarena")) {
                Location center = p.getLocation().getBlock().getLocation();
                DuelModes.buildArenaStructure(center, info.id());
                int offset = info.id().equals("sumo") ? 5 : 11;

                Location p1 = center.clone().add(0.5, 0, -offset); p1.setYaw(0f); p1.setPitch(0f);
                Location p2 = center.clone().add(0.5, 0, offset); p2.setYaw(180f); p2.setPitch(0f);

                saveArenaLoc(info.id(), "pos1", p1);
                saveArenaLoc(info.id(), "pos2", p2);
                p.teleport(center.clone().add(0.5, 2, 0.5));
                p.sendMessage(prefix + color("&#00FF66Арена для режима " + info.title() + " &#00FF66построена!"));
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
            setupStep.remove(p.getUniqueId());
            openModeMenu(p, null);
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
            startDuel(challenger, p, req);
            return true;
        }
        if (list.get(0).equalsIgnoreCase("leave")) {
            modeQueues.values().remove(p.getUniqueId());
            p.sendMessage(prefix + color("&eВы вышли из очереди поиска дуэли."));
            return true;
        }

        Player target = Bukkit.getPlayer(list.get(0));
        if (target == null || target.equals(p)) {
            p.sendMessage(prefix + color("&cИгрок не в сети! Введите &#00FF66/duel &cдля поиска случайного соперника."));
            return true;
        }
        if (activeDuels.containsKey(target.getUniqueId())) {
            p.sendMessage(prefix + color("&cЭтот игрок уже сражается на арене!"));
            return true;
        }

        setupStep.put(p.getUniqueId(), new DuelSetup(target.getUniqueId(), "mace"));
        openModeMenu(p, target.getName());
        return true;
    }

    private void openModeMenu(Player p, String targetName) {
        String title = targetName != null
                ? color("&#0055ff&l⚔ Шаг 1: Режим с &#00FF66" + targetName)
                : color("&#0055ff&l⚡ &#00E5FF&lВЫБОР РЕЖИМА ДУЭЛИ");
        Inventory inv = Bukkit.createInventory(null, 45, title);

        ItemStack blue = item(Material.BLUE_STAINED_GLASS_PANE, "&#0055ff▪", null);
        ItemStack cyan = item(Material.LIGHT_BLUE_STAINED_GLASS_PANE, "&#00E5FF▫", null);
        for (int s : new int[]{0, 8, 36, 44}) inv.setItem(s, blue);
        for (int s : new int[]{1, 2, 3, 5, 6, 7, 9, 17, 18, 26, 27, 35, 37, 38, 39, 41, 42, 43}) inv.setItem(s, cyan);

        inv.setItem(4, item(Material.NETHER_STAR, "&#00E5FF&l✦ ДУЭЛЬНЫЙ КЛУБ MIR-TIME ✦", List.of(
                "&7Выберите любой из &#00FF669 PvP-режимов&7!",
                targetName != null ? "&fДалее вы выберете &#FFD700ставку на бой &f(Иксы, Токены, Монеты или Лут)!" : "&#00FF66▶ Нажмите на режим для быстрого поиска!"
        )));

        int[] slots = {11, 12, 13, 14, 15, 21, 22, 23, 31};
        for (int i = 0; i < DuelModes.MODES.size(); i++) {
            DuelModes.ModeInfo m = DuelModes.MODES.get(i);
            List<String> lore = new ArrayList<>(m.desc());
            lore.add("");
            if (targetName == null) {
                UUID inQ = modeQueues.get(m.id());
                lore.add(inQ != null ? "&#00E5FF⚡ В очереди: 1 игрок (Нажмите для боя!)" : "&7В очереди: 0 игроков");
            }
            lore.add("&#00FF66▶ Нажмите, чтобы выбрать");
            inv.setItem(slots[i], item(m.icon(), m.title(), lore));
        }

        inv.setItem(40, item(Material.BARRIER, "&c&l✖ Выйти из очереди / Закрыть", null));
        p.openInventory(inv);
    }

    private void openBetMenu(Player p, String targetName, String modeId) {
        Inventory inv = Bukkit.createInventory(null, 36, color("&#0055ff&l💰 Шаг 2: Ставка с &#00FF66" + targetName));
        ItemStack glass = item(Material.BLACK_STAINED_GLASS_PANE, " ", null);
        for (int i = 0; i < 36; i++) inv.setItem(i, glass);

        inv.setItem(4, item(Material.LIME_DYE, "&#00FF66&l⚔ Дружеский бой (Без ставки)", List.of("&7Тренировочный поединок на китах,", "&7вещи и баланс не тратятся.", "", "&#00FF66▶ Нажмите для отправки вызова")));

        inv.setItem(10, item(Material.NETHER_STAR, "&#00E5FF&lСтавка: 5 Иксов 🅧", List.of("&7Выигрыш: &#00E5FF9 Иксов &8(с учётом комиссии)")));
        inv.setItem(11, item(Material.NETHER_STAR, "&#00E5FF&lСтавка: 15 Иксов 🅧", List.of("&7Выигрыш: &#00E5FF28 Иксов &8(с учётом комиссии)")));
        inv.setItem(12, item(Material.NETHER_STAR, "&#00E5FF&lСтавка: 50 Иксов 🅧", List.of("&7Выигрыш: &#00E5FF95 Иксов &8(с учётом комиссии)")));

        inv.setItem(14, item(Material.EMERALD, "&#00FF66&lСтавка: 500 Токенов ⛁", List.of("&7Выигрыш: &#00FF66950 Токенов")));
        inv.setItem(15, item(Material.EMERALD, "&#00FF66&lСтавка: 2.500 Токенов ⛁", List.of("&7Выигрыш: &#00FF664.750 Токенов")));
        inv.setItem(16, item(Material.EMERALD, "&#00FF66&lСтавка: 10.000 Токенов ⛁", List.of("&7Выигрыш: &#00FF6619.000 Токенов")));

        inv.setItem(19, item(Material.GOLD_INGOT, "&e&lСтавка: 25.000 Монет 🪙", List.of("&7Выигрыш: &e47.500 Монет")));
        inv.setItem(20, item(Material.GOLD_INGOT, "&e&lСтавка: 100.000 Монет 🪙", List.of("&7Выигрыш: &e190.000 Монет")));
        inv.setItem(21, item(Material.GOLD_BLOCK, "&e&lСтавка: 500.000 Монет 🪙", List.of("&7Выигрыш: &e950.000 Монет")));

        inv.setItem(24, item(Material.CHEST, "&c&l☠ ИГРА НА СВОЙ ИНВЕНТАРЬ (ЛУТ)", List.of(
                "&cВНИМАНИЕ! Опасный режим!",
                "&7Вы дерётесь в своей текущей броне и с вещами.",
                "&7Победитель забирает &#00FF66ВСЕ ВЕЩИ &7проигравшего!",
                "",
                "&c▶ Нажмите, чтобы рискнуть лутом"
        )));

        p.openInventory(inv);
    }
    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String title = e.getView().getTitle();
        String prefix = color(getConfig().getString("prefix", "&#0055ff&l⚡ &#00E5FF&lMIR-TIME &8» "));

        if (title.contains("Шаг 1: Режим") || title.contains("ВЫБОР РЕЖИМА ДУЭЛИ")) {
            e.setCancelled(true);
            int slot = e.getRawSlot();
            if (slot == 40) { modeQueues.values().remove(p.getUniqueId()); p.closeInventory(); return; }

            int[] slots = {11, 12, 13, 14, 15, 21, 22, 23, 31};
            String chosenMode = null;
            for (int i = 0; i < slots.length; i++) if (slots[i] == slot) chosenMode = DuelModes.MODES.get(i).id();
            if (chosenMode == null) return;

            DuelSetup setup = setupStep.get(p.getUniqueId());
            if (setup != null) {
                Player target = Bukkit.getPlayer(setup.target());
                if (target == null) { p.closeInventory(); return; }
                setupStep.put(p.getUniqueId(), new DuelSetup(target.getUniqueId(), chosenMode));
                openBetMenu(p, target.getName(), chosenMode);
                return;
            }

            p.closeInventory();
            modeQueues.values().remove(p.getUniqueId());
            UUID waitingId = modeQueues.get(chosenMode);
            if (waitingId != null) {
                Player opponent = Bukkit.getPlayer(waitingId);
                modeQueues.remove(chosenMode);
                if (opponent != null && opponent.isOnline() && !opponent.equals(p)) {
                    startDuel(opponent, p, new DuelRequest(opponent.getUniqueId(), p.getUniqueId(), chosenMode, "NONE", 0));
                    return;
                }
            }
            modeQueues.put(chosenMode, p.getUniqueId());
            p.sendMessage(prefix + color("&#00FF66Вы встали в очередь режима " + DuelModes.getMode(chosenMode).title() + "&#00FF66! Ожидаем соперника..."));
            return;
        }

        if (title.contains("Шаг 2: Ставка")) {
            e.setCancelled(true);
            DuelSetup setup = setupStep.remove(p.getUniqueId());
            if (setup == null) { p.closeInventory(); return; }

            String bType = null;
            int bAmt = 0;
            switch (e.getRawSlot()) {
                case 4 -> { bType = "NONE"; bAmt = 0; }
                case 10 -> { bType = "IKSY"; bAmt = 5; }
                case 11 -> { bType = "IKSY"; bAmt = 15; }
                case 12 -> { bType = "IKSY"; bAmt = 50; }
                case 14 -> { bType = "TOKENS"; bAmt = 500; }
                case 15 -> { bType = "TOKENS"; bAmt = 2500; }
                case 16 -> { bType = "TOKENS"; bAmt = 10000; }
                case 19 -> { bType = "MONEY"; bAmt = 25000; }
                case 20 -> { bType = "MONEY"; bAmt = 100000; }
                case 21 -> { bType = "MONEY"; bAmt = 500000; }
                case 24 -> { bType = "LOOT"; bAmt = 1; }
            }
            if (bType == null) return;
            p.closeInventory();

            Player target = Bukkit.getPlayer(setup.target());
            if (target == null) return;

            if (!checkBalance(p, bType, bAmt)) {
                p.sendMessage(prefix + color("&cУ вас недостаточно средств для этой ставки!"));
                return;
            }

            DuelRequest req = new DuelRequest(p.getUniqueId(), target.getUniqueId(), setup.mode(), bType, bAmt);
            incomingRequests.put(target.getUniqueId(), req);

            DuelModes.ModeInfo info = DuelModes.getMode(setup.mode());
            String betDesc = formatBet(bType, bAmt);

            p.sendMessage(prefix + color("&#00FF66Вызов отправлен игроку &f" + target.getName() + "&7! Режим: " + info.title() + " &8| " + betDesc));
            target.sendMessage(color("&#0055ff&l&m                                                  "));
            target.sendMessage(color(" &#0055ff&l⚔ &#00E5FF&lВЫЗОВ НА ДУЭЛЬ &8| &fОт: &#00FF66" + p.getName()));
            target.sendMessage(color(" &#0055ff● &fРежим: " + info.title() + " &8| &fУсловия: " + betDesc));
            target.sendMessage(Component.text(color(" &#00FF66&l[✔ ПРИНЯТЬ ВЫЗОВ — НАЖМИТЕ СЮДА]")).clickEvent(ClickEvent.runCommand("/duel accept")));
            target.sendMessage(color("&#0055ff&l&m                                                  "));
            target.playSound(target.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        }
    }
    private void startDuel(Player p1, Player p2, DuelRequest req) {
        String prefix = color(getConfig().getString("prefix", "&#0055ff&l⚡ &#00E5FF&lMIR-TIME &8» "));
        if (!checkBalance(p1, req.betType(), req.betAmount()) || !checkBalance(p2, req.betType(), req.betAmount())) {
            p1.sendMessage(prefix + color("&cУ одного из игроков не хватает средств для ставки!"));
            p2.sendMessage(prefix + color("&cУ одного из игроков не хватает средств для ставки!"));
            return;
        }
        takeBet(p1, req.betType(), req.betAmount());
        takeBet(p2, req.betType(), req.betAmount());

        modeQueues.values().remove(p1.getUniqueId());
        modeQueues.values().remove(p2.getUniqueId());

        if (!req.betType().equals("LOOT")) {
            saveState(p1);
            saveState(p2);
            DuelModes.equip(p1, req.mode());
            DuelModes.equip(p2, req.mode());
        } else {
            savedStates.put(p1.getUniqueId(), new SavedState(p1.getLocation().clone(), null, null, null));
            savedStates.put(p2.getUniqueId(), new SavedState(p2.getLocation().clone(), null, null, null));
        }

        activeDuels.put(p1.getUniqueId(), p2.getUniqueId());
        activeDuels.put(p2.getUniqueId(), p1.getUniqueId());
        activeMode.put(p1.getUniqueId(), req.mode());
        activeMode.put(p2.getUniqueId(), req.mode());
        activeMeta.put(p1.getUniqueId(), req);
        activeMeta.put(p2.getUniqueId(), req);

        p1.setGameMode(GameMode.SURVIVAL);
        p2.setGameMode(GameMode.SURVIVAL);
        p1.teleport(getArenaLoc(req.mode(), "pos1"));
        p2.teleport(getArenaLoc(req.mode(), "pos2"));

        DuelModes.ModeInfo info = DuelModes.getMode(req.mode());
        p1.sendTitle(color(info.title()), color("&fПротивник: &#00E5FF" + p2.getName()), 5, 45, 10);
        p2.sendTitle(color(info.title()), color("&fПротивник: &#00E5FF" + p1.getName()), 5, 45, 10);
        p1.playSound(p1.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.8f, 1.2f);
        p2.playSound(p2.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.8f, 1.2f);

        startBossBarTimer(p1, p2, info.title());
    }

    private void startBossBarTimer(Player p1, Player p2, String modeTitle) {
        int maxSec = getConfig().getInt("duel-time-seconds", 240);
        BossBar bar1 = Bukkit.createBossBar(color(modeTitle), BarColor.BLUE, BarStyle.SEGMENTED_10);
        BossBar bar2 = Bukkit.createBossBar(color(modeTitle), BarColor.BLUE, BarStyle.SEGMENTED_10);
        bar1.addPlayer(p1);
        bar2.addPlayer(p2);
        activeBars.put(p1.getUniqueId(), bar1);
        activeBars.put(p2.getUniqueId(), bar2);

        new BukkitRunnable() {
            int rem = maxSec;
            @Override
            public void run() {
                if (!activeDuels.containsKey(p1.getUniqueId()) || !p1.isOnline() || !p2.isOnline()) {
                    bar1.removeAll(); bar2.removeAll();
                    cancel();
                    return;
                }
                rem--;
                String timeStr = String.format("%02d:%02d", rem / 60, rem % 60);
                double prog = Math.max(0.0, Math.min(1.0, (double) rem / maxSec));

                bar1.setProgress(prog);
                bar1.setTitle(color(modeTitle + " &8| &fВраг: &#00FF66" + p2.getName() + " &c(" + (int) Math.ceil(p2.getHealth()) + "❤) &8| &e⏳ " + timeStr));

                bar2.setProgress(prog);
                bar2.setTitle(color(modeTitle + " &8| &fВраг: &#00FF66" + p1.getName() + " &c(" + (int) Math.ceil(p1.getHealth()) + "❤) &8| &e⏳ " + timeStr));

                if (rem <= 0) {
                    cancel();
                    drawDuel(p1, p2);
                }
            }
        }.runTaskTimer(this, 20L, 20L);
    }

    private void drawDuel(Player p1, Player p2) {
        DuelRequest req = activeMeta.get(p1.getUniqueId());
        cleanupDuelData(p1.getUniqueId(), p2.getUniqueId());
        restoreState(p1);
        restoreState(p2);
        if (req != null && req.betAmount() > 0 && !req.betType().equals("LOOT")) {
            giveReward(p1, req.betType(), req.betAmount());
            giveReward(p2, req.betType(), req.betAmount());
        }
        Bukkit.broadcastMessage(color("&#0055ff&l⚡ &#00E5FF&lMIR-TIME &8» &eВремя вышло! Дуэль между &f" + p1.getName() + " &eи &f" + p2.getName() + " &eзавершилась ничьей."));
    }
    private void finishDuel(Player winner, Player loser) {
        UUID wId = winner.getUniqueId();
        UUID lId = loser.getUniqueId();
        String modeId = activeMode.getOrDefault(wId, "classic");
        DuelRequest req = activeMeta.get(wId);

        cleanupDuelData(wId, lId);

        if (req != null && req.betType().equals("LOOT")) {
            winner.sendMessage(color(getConfig().getString("prefix") + "&#00FF66У вас есть 10 секунд, чтобы собрать трофеи с арены!"));
            Bukkit.getScheduler().runTaskLater(this, () -> { if (winner.isOnline()) restoreState(winner); }, 200L);
        } else {
            restoreState(winner);
            if (loser.isOnline()) Bukkit.getScheduler().runTaskLater(this, () -> restoreState(loser), 2L);
        }

        if ((modeId.equals("crystal") || modeId.equals("uhc")) && getConfig().contains("arenas." + modeId + ".pos1")) {
            Location l1 = getArenaLoc(modeId, "pos1");
            Location l2 = getArenaLoc(modeId, "pos2");
            Location center = l1.clone().add(l2).multiply(0.5);
            center.setY(l1.getY());
            Bukkit.getScheduler().runTaskLater(this, () -> DuelModes.buildArenaStructure(center, modeId), 15L);
        }

        String prizeText = "";
        if (req != null && req.betAmount() > 0 && !req.betType().equals("NONE") && !req.betType().equals("LOOT")) {
            int totalBank = req.betAmount() * 2;
            int comm = getConfig().getInt("commission-percent", 5);
            int winAmount = Math.max(1, totalBank - (totalBank * comm / 100));
            giveReward(winner, req.betType(), winAmount);
            prizeText = " &8(&fВыигрыш: " + formatBet(req.betType(), winAmount) + "&8)";
        } else if (req != null && req.betType().equals("LOOT")) {
            prizeText = " &8(&cНа свой инвентарь&8)";
        }

        DuelModes.ModeInfo info = DuelModes.getMode(modeId);
        Bukkit.broadcastMessage(color("&#0055ff&l⚡ &#00E5FF&lMIR-TIME &8» &#00FF66" + winner.getName() + " &fпобедил &c" + loser.getName() + " &fв дуэли " + info.title() + "!" + prizeText));
    }

    private void cleanupDuelData(UUID u1, UUID u2) {
        activeDuels.remove(u1); activeDuels.remove(u2);
        activeMode.remove(u1); activeMode.remove(u2);
        activeMeta.remove(u1); activeMeta.remove(u2);
        BossBar b1 = activeBars.remove(u1); if (b1 != null) b1.removeAll();
        BossBar b2 = activeBars.remove(u2); if (b2 != null) b2.removeAll();
    }

    @EventHandler public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (!"sumo".equals(activeMode.get(p.getUniqueId()))) return;
        if (p.getLocation().getY() < getArenaLoc("sumo", "pos1").getY() - 3.0) {
            UUID oppId = activeDuels.get(p.getUniqueId());
            Player winner = oppId != null ? Bukkit.getPlayer(oppId) : null;
            if (winner != null) finishDuel(winner, p);
        }
    }

    @EventHandler public void onRegen(EntityRegainHealthEvent e) {
        if (e.getEntity() instanceof Player p && "uhc".equals(activeMode.get(p.getUniqueId()))) {
            if (e.getRegainReason() == EntityRegainHealthEvent.RegainReason.SATIATED || e.getRegainReason() == EntityRegainHealthEvent.RegainReason.REGEN) e.setCancelled(true);
        }
    }

    @EventHandler public void onDeath(PlayerDeathEvent e) {
        Player dead = e.getEntity();
        UUID oppId = activeDuels.get(dead.getUniqueId());
        if (oppId == null) return;

        DuelRequest req = activeMeta.get(dead.getUniqueId());
        if (req == null || !req.betType().equals("LOOT")) {
            e.getDrops().clear();
            e.setDroppedExp(0);
            e.setKeepInventory(true);
        }
        Player winner = Bukkit.getPlayer(oppId);
        if (winner != null) finishDuel(winner, dead);
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) {
        Player left = e.getPlayer();
        modeQueues.values().remove(left.getUniqueId());
        UUID oppId = activeDuels.get(left.getUniqueId());
        if (oppId == null) return;
        restoreState(left);
        Player winner = Bukkit.getPlayer(oppId);
        if (winner != null) finishDuel(winner, left);
    }

    @EventHandler public void onCmd(PlayerCommandPreprocessEvent e) {
        if (activeDuels.containsKey(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(color(getConfig().getString("prefix") + "&cКоманды запрещены во время дуэли!"));
        }
    }
    private void saveState(Player p) {
        savedStates.put(p.getUniqueId(), new SavedState(p.getLocation().clone(), p.getInventory().getContents().clone(), p.getInventory().getArmorContents().clone(), p.getInventory().getItemInOffHand().clone()));
    }

    private void restoreState(Player p) {
        SavedState st = savedStates.remove(p.getUniqueId());
        if (st == null) return;
        if (st.inv() != null) {
            p.getActivePotionEffects().forEach(ef -> p.removePotionEffect(ef.getType()));
            p.getInventory().clear();
            p.getInventory().setContents(st.inv());
            p.getInventory().setArmorContents(st.armor());
            p.getInventory().setItemInOffHand(st.offhand());
        }
        p.setHealth(20.0);
        p.setFoodLevel(20);
        p.teleport(st.loc());
        p.updateInventory();
    }

    private boolean checkBalance(Player p, String type, int amt) {
        if (type.equals("NONE") || type.equals("LOOT") || amt <= 0) return true;
        String ph = switch (type) {
            case "IKSY" -> "%gemseconomy_balance_default%";
            case "TOKENS" -> "%playerpoints_points%";
            case "MONEY" -> "%vault_eco_balance_fixed%";
            default -> "";
        };
        try {
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
                Method m = papi.getMethod("setPlaceholders", Player.class, String.class);
                String res = (String) m.invoke(null, p, ph);
                return Double.parseDouble(res.replaceAll("[^0-9.]", "")) >= amt;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void takeBet(Player p, String type, int amt) {
        if (amt <= 0) return;
        switch (type) {
            case "IKSY" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "giks take " + p.getName() + " " + amt + " Iksy");
            case "TOKENS" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "p take " + p.getName() + " " + amt);
            case "MONEY" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "eco take " + p.getName() + " " + amt);
        }
    }

    private void giveReward(Player p, String type, int amt) {
        if (amt <= 0) return;
        switch (type) {
            case "IKSY" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "giks give " + p.getName() + " " + amt + " Iksy");
            case "TOKENS" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "p give " + p.getName() + " " + amt);
            case "MONEY" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "eco give " + p.getName() + " " + amt);
        }
    }

    private String formatBet(String type, int amt) {
        return switch (type) {
            case "IKSY" -> "&#00E5FF&l" + amt + " Иксов 🅧";
            case "TOKENS" -> "&#00FF66&l" + amt + " Токенов ⛁";
            case "MONEY" -> "&e&l" + amt + " Монет 🪙";
            case "LOOT" -> "&c&lНа свой инвентарь";
            default -> "&7Без ставки";
        };
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
        return new Location(w, getConfig().getDouble(path + ".x", -200.5), getConfig().getDouble(path + ".y", 110.0), getConfig().getDouble(path + ".z", pos.equals("pos1") ? 90.0 : 110.0), (float) getConfig().getDouble(path + ".yaw", pos.equals("pos1") ? 0.0 : 180.0), (float) getConfig().getDouble(path + ".pitch", 0.0));
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

    @Override public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
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
