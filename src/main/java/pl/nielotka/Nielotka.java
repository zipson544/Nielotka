package pl.nielotka;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class Nielotka extends JavaPlugin implements Listener, TabExecutor {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private NamespacedKey rodKey;
    private BukkitTask ticker;

    /** Aktywne klatwy: klucz = UUID zlapanego gracza. */
    private final Map<UUID, Curse> curses = new HashMap<>();
    /** Cooldowny: klucz = UUID lapiacego, wartosc = czas konca (ms). */
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    /** Ograniczenie spamu wiadomoscia o zablokowanym locie. */
    private final Map<UUID, Long> glideMsgAt = new HashMap<>();

    private static final class Curse {
        final UUID shooter;
        final UUID target;
        final long endAt;
        final long total;
        final BossBar bar;

        Curse(UUID shooter, UUID target, long endAt, long total, BossBar bar) {
            this.shooter = shooter;
            this.target = target;
            this.endAt = endAt;
            this.total = total;
            this.bar = bar;
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        rodKey = new NamespacedKey(this, "nielotka_rod");
        Bukkit.getPluginManager().registerEvents(this, this);
        var cmd = getCommand("nielotka");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        ticker = Bukkit.getScheduler().runTaskTimer(this, this::tick, 2L, 2L);
    }

    @Override
    public void onDisable() {
        if (ticker != null) {
            ticker.cancel();
        }
        for (Curse c : new ArrayList<>(curses.values())) {
            end(c, "silent");
        }
        curses.clear();
    }

    // ------------------------------------------------------------------ rod item

    public ItemStack createRod() {
        Material mat = Material.matchMaterial(getConfig().getString("rod.material", "FISHING_ROD"));
        if (mat == null) {
            mat = Material.FISHING_ROD;
        }
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(plain(getConfig().getString("rod.name", "&5&lWędka nielota")));
        List<Component> lore = new ArrayList<>();
        for (String line : getConfig().getStringList("rod.lore")) {
            lore.add(plain(line));
        }
        meta.lore(lore);

        int cmd = getConfig().getInt("rod.custom-model-data", 0);
        if (cmd > 0) {
            meta.setCustomModelData(cmd);
        }
        meta.setUnbreakable(getConfig().getBoolean("rod.unbreakable", true));
        for (String f : getConfig().getStringList("rod.flags")) {
            try {
                meta.addItemFlags(ItemFlag.valueOf(f.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // nieznana flaga na tej wersji - pomijamy
            }
        }
        meta.getPersistentDataContainer().set(rodKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isRod(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(rodKey, PersistentDataType.BYTE);
    }

    private boolean holdsRod(Player p) {
        return isRod(p.getInventory().getItemInMainHand()) || isRod(p.getInventory().getItemInOffHand());
    }

    // ------------------------------------------------------------------ catching

    private void tryCatch(Player shooter, Player target, boolean verbose) {
        if (shooter.getUniqueId().equals(target.getUniqueId()) || !holdsRod(shooter)) {
            return;
        }
        long now = System.currentTimeMillis();
        Long cdEnd = cooldowns.get(shooter.getUniqueId());
        if (cdEnd != null && cdEnd > now) {
            if (verbose) {
                send(shooter, "messages.cooldown", shooter, target, cdEnd - now);
            }
            return;
        }

        Curse old = curses.get(target.getUniqueId());
        if (old != null) {
            end(old, "silent");
        }

        long total = getConfig().getLong("effect.duration", 15) * 1000L;
        long cooldown = getConfig().getLong("effect.cooldown", 30) * 1000L;
        cooldowns.put(shooter.getUniqueId(), now + cooldown);

        BossBar bar = null;
        if (getConfig().getBoolean("bossbar.enabled", true)) {
            bar = BossBar.bossBar(
                    LEGACY.deserialize(fmt(getConfig().getString("bossbar.title", ""), shooter, target, total)),
                    1.0f,
                    parseColor(getConfig().getString("bossbar.color", "RED")),
                    parseOverlay(getConfig().getString("bossbar.style", "SOLID")));
            target.showBossBar(bar);
        }

        Curse curse = new Curse(shooter.getUniqueId(), target.getUniqueId(), now + total, total, bar);
        curses.put(target.getUniqueId(), curse);

        if (getConfig().getBoolean("effect.stop-current-glide", true) && target.isGliding()) {
            target.setGliding(false);
        }

        send(shooter, "messages.shooter.activate", shooter, target, total);
        send(target, "messages.target.activate", shooter, target, total);
        sound(shooter, "sounds.activate");
        sound(target, "sounds.curse");
    }

    private void end(Curse c, String reason) {
        curses.remove(c.target);
        Player target = Bukkit.getPlayer(c.target);
        Player shooter = Bukkit.getPlayer(c.shooter);
        if (target != null && c.bar != null) {
            target.hideBossBar(c.bar);
        }
        if (reason.equals("silent")) {
            return;
        }
        if (target != null) {
            send(target, "messages.target." + reason, shooter, target, 0);
        }
        if (shooter != null) {
            send(shooter, "messages.shooter." + reason, shooter, target, 0);
        }
    }

    private void tick() {
        if (curses.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean needRod = getConfig().getBoolean("effect.end-when-rod-not-in-hand", true);

        for (Curse c : new ArrayList<>(curses.values())) {
            Player target = Bukkit.getPlayer(c.target);
            Player shooter = Bukkit.getPlayer(c.shooter);

            if (target == null || shooter == null || target.isDead() || shooter.isDead()) {
                end(c, "cancel");
            } else if (now >= c.endAt) {
                end(c, "timeout");
            } else if (needRod && !holdsRod(shooter)) {
                end(c, "cancel");
            } else if (c.bar != null) {
                long rem = c.endAt - now;
                c.bar.progress(Math.max(0f, Math.min(1f, rem / (float) c.total)));
                c.bar.name(LEGACY.deserialize(fmt(getConfig().getString("bossbar.title", ""), shooter, target, rem)));
            }
        }
    }

    // ------------------------------------------------------------------ events

    /** Haczyk trafil w gracza. */
    @EventHandler(ignoreCancelled = true)
    public void onHookHit(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof FishHook hook)) {
            return;
        }
        if (!(e.getHitEntity() instanceof Player target)) {
            return;
        }
        ProjectileSource src = hook.getShooter();
        if (src instanceof Player shooter) {
            tryCatch(shooter, target, false);
        }
    }

    @EventHandler
    public void onFish(PlayerFishEvent e) {
        Player p = e.getPlayer();
        switch (e.getState()) {
            case FISHING -> {
                if (!getConfig().getBoolean("effect.block-cast-on-cooldown", true) || !holdsRod(p)) {
                    return;
                }
                Long cdEnd = cooldowns.get(p.getUniqueId());
                long now = System.currentTimeMillis();
                if (cdEnd != null && cdEnd > now) {
                    e.setCancelled(true);
                    send(p, "messages.cooldown", p, null, cdEnd - now);
                }
            }
            // zapasowo: gdy ktos zwinie zylke z zahaczonym graczem
            case CAUGHT_ENTITY -> {
                if (e.getCaught() instanceof Player target) {
                    tryCatch(p, target, false);
                }
            }
            default -> { }
        }
    }

    /** Blokada startu lotu elytra. */
    @EventHandler(ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent e) {
        if (!e.isGliding() || !(e.getEntity() instanceof Player p)) {
            return;
        }
        Curse c = curses.get(p.getUniqueId());
        if (c == null) {
            return;
        }
        e.setCancelled(true);

        long now = System.currentTimeMillis();
        Long last = glideMsgAt.get(p.getUniqueId());
        if (last == null || now - last > 1000) {
            glideMsgAt.put(p.getUniqueId(), now);
            send(p, "messages.target.glide-blocked", Bukkit.getPlayer(c.shooter), p, c.endAt - now);
        }
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("nielotka.admin")) {
            sender.sendMessage(LEGACY.deserialize(getConfig().getString("messages.no-permission", "")));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(LEGACY.deserialize(getConfig().getString("messages.usage", "")));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                reloadConfig();
                sender.sendMessage(LEGACY.deserialize(getConfig().getString("messages.reloaded", "")));
            }
            case "give" -> {
                Player target = null;
                if (args.length > 1) {
                    target = Bukkit.getPlayerExact(args[1]);
                } else if (sender instanceof Player self) {
                    target = self;
                }
                if (target == null) {
                    sender.sendMessage(LEGACY.deserialize(getConfig().getString("messages.player-not-found", "")));
                    return true;
                }
                int amount = 1;
                if (args.length > 2) {
                    try {
                        amount = Math.max(1, Math.min(36, Integer.parseInt(args[2])));
                    } catch (NumberFormatException ignored) {
                        amount = 1;
                    }
                }
                for (int i = 0; i < amount; i++) {
                    Map<Integer, ItemStack> left = target.getInventory().addItem(createRod());
                    for (ItemStack rest : left.values()) {
                        target.getWorld().dropItemNaturally(target.getLocation(), rest);
                    }
                }
                sender.sendMessage(LEGACY.deserialize(
                        getConfig().getString("messages.given", "").replace("{TARGET}", target.getName())));
            }
            default -> sender.sendMessage(LEGACY.deserialize(getConfig().getString("messages.usage", "")));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("nielotka.admin")) {
            return out;
        }
        if (args.length == 1) {
            for (String s : List.of("give", "reload")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(p.getName());
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    /** Tekst na przedmiot: bez domyslnej kursywy. */
    private Component plain(String s) {
        return LEGACY.deserialize(s).decoration(TextDecoration.ITALIC, false);
    }

    private String fmt(String s, Player shooter, Player target, long remainingMs) {
        if (s == null) {
            return "";
        }
        long secs = Math.max(0, (remainingMs + 999) / 1000);
        return s.replace("{TIME}", String.valueOf(secs))
                .replace("{SHOOTER}", shooter != null ? shooter.getName() : "")
                .replace("{TARGET}", target != null ? target.getName() : "");
    }

    /** Wysyla title / subtitle / chat / actionbar z sekcji config. Puste pola sa pomijane. */
    private void send(Player to, String path, Player shooter, Player target, long remainingMs) {
        ConfigurationSection sec = getConfig().getConfigurationSection(path);
        if (sec == null) {
            return;
        }
        Player other = (shooter != null && shooter.getUniqueId().equals(to.getUniqueId())) ? target : shooter;
        String nick = other != null ? other.getName() : "";
        String title = fmt(sec.getString("title", ""), shooter, target, remainingMs).replace("{nick}", nick);
        String subtitle = fmt(sec.getString("subtitle", ""), shooter, target, remainingMs).replace("{nick}", nick);
        String chat = fmt(sec.getString("chat", ""), shooter, target, remainingMs).replace("{nick}", nick);
        String action = fmt(sec.getString("actionbar", ""), shooter, target, remainingMs).replace("{nick}", nick);

        if (!title.isEmpty() || !subtitle.isEmpty()) {
            to.showTitle(Title.title(LEGACY.deserialize(title), LEGACY.deserialize(subtitle)));
        }
        if (!chat.isEmpty()) {
            to.sendMessage(LEGACY.deserialize(chat));
        }
        if (!action.isEmpty()) {
            to.sendActionBar(LEGACY.deserialize(action));
        }
    }

    private void sound(Player p, String path) {
        ConfigurationSection sec = getConfig().getConfigurationSection(path);
        if (sec == null || !sec.getBoolean("enabled", true)) {
            return;
        }
        String key = sec.getString("sound", "");
        if (key.isEmpty()) {
            return;
        }
        p.playSound(p.getLocation(), key, SoundCategory.PLAYERS,
                (float) sec.getDouble("volume", 1.0), (float) sec.getDouble("pitch", 1.0));
    }

    private BossBar.Color parseColor(String s) {
        try {
            return BossBar.Color.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return BossBar.Color.RED;
        }
    }

    private BossBar.Overlay parseOverlay(String s) {
        return switch (s.toUpperCase(Locale.ROOT)) {
            case "SEGMENTED_6" -> BossBar.Overlay.NOTCHED_6;
            case "SEGMENTED_10" -> BossBar.Overlay.NOTCHED_10;
            case "SEGMENTED_12" -> BossBar.Overlay.NOTCHED_12;
            case "SEGMENTED_20" -> BossBar.Overlay.NOTCHED_20;
            default -> BossBar.Overlay.PROGRESS;
        };
    }
}
