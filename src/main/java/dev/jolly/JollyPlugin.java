package dev.jolly;

import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.event.player.PlayerTradeEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class JollyPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final int CONFIG_VERSION = 3;
    /** Bump when the hat item's components change so old hats get rewritten. */
    private static final int HAT_VERSION = 3;

    record Drop(Material mat, double weight) {}

    record Tier(int threshold, String color, int maxDura, double armor, double tough, int unbreaking,
                boolean glint, boolean snow, double bonusChance, int bonusAmount,
                List<Drop> table, double totalWeight, Map<PotionEffectType, Integer> effects) {}

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final List<Tier> tiers = new ArrayList<>();
    private NamespacedKey JOLLY, HAT, HAT_TIER, HAT_VER, GIVEN, PLACED, AWARDED, RECIPE, M_ARMOR, M_TOUGH;

    // runtime state
    private final Map<UUID, Double> walkDist = new HashMap<>();
    private final Map<UUID, Double> walkPts = new HashMap<>();
    private final Map<UUID, Integer> lastMove = new HashMap<>();
    private final Map<UUID, Integer> lastGainSound = new HashMap<>();
    private final Map<UUID, Integer> playSecs = new HashMap<>();
    private final Map<String, Long> cooldowns = new HashMap<>();
    private final Set<UUID> needsHat = new HashSet<>();

    // cached config
    private int maxJolly;
    private boolean ignoreCreative, giveFirstJoin, recipeDiscover;
    private boolean keepJolly, destroyHat, hatOnRespawn;
    private boolean tabEnabled, barEnabled, displayEnabled;
    private String tabFormat, barFormat;

    private boolean gainSoundOn, tierSoundOn, tierSoundAll, announceOn;
    private String gainSound, tierSound, announceMsg;
    private float gainVol, gainPitch, tierVol, tierPitch;
    private int gainGap;

    private boolean walkEnabled, mineEnabled, giftEnabled, chatEnabled;
    private double walkBlocks, walkPoints, snowMult;
    private Set<String> snowBiomes = Set.of();
    private final Map<Material, Integer> minePoints = new EnumMap<>(Material.class);
    private int giftPoints, chatPoints;
    private long giftCdMs, chatCdMs;
    private volatile List<String> chatPhrases = List.of();

    private boolean rareOn, rareOnce, decorOn;
    private final Map<Material, Integer> rarePoints = new EnumMap<>(Material.class);
    private final Map<Material, Integer> decorPoints = new EnumMap<>(Material.class);
    private boolean breedOn, snowballOn, tradeOn, foodOn, fishOn, advOn, playOn, killOn;
    private int breedPts, snowballPts, tradePts, fishPts, advPts, playMinutes, playPts, killPts;
    private long breedCd, snowballCd, tradeCd, foodCd;
    private final Map<Material, Integer> foodPoints = new EnumMap<>(Material.class);
    private final Map<EntityType, Integer> bossPoints = new EnumMap<>(EntityType.class);

    // ------------------------------------------------------------------ lifecycle

    @Override public void onEnable() {
        saveDefaultConfig();
        if (getConfig().getInt("config-version", 0) < CONFIG_VERSION) {
            // Old config from an earlier Jolly version: keep it as config-old.yml and write the new defaults.
            File f = new File(getDataFolder(), "config.yml");
            File bak = new File(getDataFolder(), "config-old.yml");
            if (bak.exists()) bak.delete();
            if (f.renameTo(bak)) {
                saveDefaultConfig();
                getLogger().warning("Old config.yml moved to config-old.yml; wrote the new default config.yml.");
            }
        }

        JOLLY = new NamespacedKey(this, "jolly");
        HAT = new NamespacedKey(this, "santa_hat");
        HAT_TIER = new NamespacedKey(this, "hat_tier");
        HAT_VER = new NamespacedKey(this, "hat_ver");
        GIVEN = new NamespacedKey(this, "hat_given");
        PLACED = new NamespacedKey(this, "placed_ores");
        AWARDED = new NamespacedKey(this, "awarded_blocks");
        RECIPE = new NamespacedKey(this, "santa_hat_recipe");
        M_ARMOR = new NamespacedKey(this, "hat_armor");
        M_TOUGH = new NamespacedKey(this, "hat_tough");

        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("jolly").setExecutor(this);
        loadSettings();

        Bukkit.getScheduler().runTaskTimer(this, this::slowTick, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::fastTick, 2L, 2L);
    }

    private void loadSettings() {
        reloadConfig();
        FileConfiguration c = getConfig();
        maxJolly = c.getInt("max-jolliness", 1_000_000);
        ignoreCreative = c.getBoolean("ignore-creative", true);
        giveFirstJoin = c.getBoolean("give-hat-on-first-join", false);
        recipeDiscover = c.getBoolean("recipe.discover-on-join", true);

        keepJolly = c.getBoolean("death.keep-jolliness", true);
        destroyHat = c.getBoolean("death.destroy-hat", false);
        hatOnRespawn = c.getBoolean("death.give-hat-on-respawn", false);

        tabEnabled = c.getBoolean("tablist.enabled", true);
        tabFormat = c.getString("tablist.format", "<name> <tiercolor>[<jolly>]</tiercolor>");
        barEnabled = c.getBoolean("actionbar.enabled", true);
        barFormat = c.getString("actionbar.format", "<gold>Jolliness: <tiercolor><jolly></tiercolor>");
        displayEnabled = c.getBoolean("hat-display.enabled", true);

        gainSoundOn = c.getBoolean("sounds.gain.enabled", true);
        gainSound = c.getString("sounds.gain.sound", "minecraft:block.amethyst_block.chime");
        gainVol = (float) c.getDouble("sounds.gain.volume", 0.5);
        gainPitch = (float) c.getDouble("sounds.gain.pitch", 1.6);
        gainGap = c.getInt("sounds.gain.min-gap-ticks", 8);
        tierSoundOn = c.getBoolean("sounds.tier-up.enabled", true);
        tierSound = c.getString("sounds.tier-up.sound", "minecraft:ui.toast.challenge_complete");
        tierVol = (float) c.getDouble("sounds.tier-up.volume", 1.0);
        tierPitch = (float) c.getDouble("sounds.tier-up.pitch", 1.0);
        tierSoundAll = c.getBoolean("sounds.tier-up.everyone", true);
        announceOn = c.getBoolean("announce.enabled", true);
        announceMsg = c.getString("announce.message", "<gold><player> reached Jolly Tier <tier>!</gold>");

        walkEnabled = c.getBoolean("gains.walking.enabled", true);
        walkBlocks = Math.max(0.0001, c.getDouble("gains.walking.blocks-per-point", 10));
        walkPoints = c.getDouble("gains.walking.points", 1);
        snowMult = c.getDouble("gains.walking.snow-multiplier", 2.0);
        snowBiomes = new HashSet<>(c.getStringList("gains.walking.snow-biomes"));

        mineEnabled = c.getBoolean("gains.mining.enabled", true);
        readMaterials(c.getConfigurationSection("gains.mining.points"), minePoints, "mining.points");

        rareOn = c.getBoolean("gains.placing-rare.enabled", true);
        rareOnce = c.getBoolean("gains.placing-rare.once-per-position", true);
        readMaterials(c.getConfigurationSection("gains.placing-rare.points"), rarePoints, "placing-rare.points");
        decorOn = c.getBoolean("gains.decorating.enabled", true);
        readMaterials(c.getConfigurationSection("gains.decorating.points"), decorPoints, "decorating.points");

        breedOn = c.getBoolean("gains.breeding.enabled", true);
        breedPts = c.getInt("gains.breeding.points", 3);
        breedCd = c.getLong("gains.breeding.cooldown-seconds", 2) * 1000L;
        snowballOn = c.getBoolean("gains.snowballs.enabled", true);
        snowballPts = c.getInt("gains.snowballs.points", 1);
        snowballCd = c.getLong("gains.snowballs.cooldown-seconds", 3) * 1000L;
        tradeOn = c.getBoolean("gains.trading.enabled", true);
        tradePts = c.getInt("gains.trading.points", 2);
        tradeCd = c.getLong("gains.trading.cooldown-seconds", 5) * 1000L;
        foodOn = c.getBoolean("gains.festive-food.enabled", true);
        foodCd = c.getLong("gains.festive-food.cooldown-seconds", 10) * 1000L;
        readMaterials(c.getConfigurationSection("gains.festive-food.points"), foodPoints, "festive-food.points");
        fishOn = c.getBoolean("gains.fishing.enabled", true);
        fishPts = c.getInt("gains.fishing.points", 1);
        advOn = c.getBoolean("gains.advancements.enabled", true);
        advPts = c.getInt("gains.advancements.points", 5);
        playOn = c.getBoolean("gains.playtime.enabled", true);
        playMinutes = Math.max(1, c.getInt("gains.playtime.minutes", 5));
        playPts = c.getInt("gains.playtime.points", 1);
        killOn = c.getBoolean("gains.mob-kills.enabled", false);
        killPts = c.getInt("gains.mob-kills.points", 1);
        bossPoints.clear();
        ConfigurationSection bp = c.getConfigurationSection("gains.mob-kills.boss-points");
        if (bp != null) for (String k : bp.getKeys(false)) {
            try { bossPoints.put(EntityType.valueOf(k.toUpperCase(Locale.ROOT)), bp.getInt(k)); }
            catch (IllegalArgumentException ex) { getLogger().warning("Unknown entity in mob-kills.boss-points: " + k); }
        }

        giftEnabled = c.getBoolean("gains.gifting.enabled", true);
        giftPoints = c.getInt("gains.gifting.points", 3);
        giftCdMs = c.getLong("gains.gifting.pair-cooldown-seconds", 300) * 1000L;

        chatEnabled = c.getBoolean("gains.chat.enabled", true);
        chatPoints = c.getInt("gains.chat.points", 5);
        chatCdMs = c.getLong("gains.chat.cooldown-seconds", 60) * 1000L;
        List<String> ph = new ArrayList<>();
        for (String s : c.getStringList("gains.chat.phrases")) ph.add(s.toLowerCase(Locale.ROOT));
        chatPhrases = List.copyOf(ph);

        loadTiers();
        registerRecipe();
        for (Player p : Bukkit.getOnlinePlayers()) { refreshAllHats(p); updateTab(p); }
    }

    private void readMaterials(ConfigurationSection sec, Map<Material, Integer> into, String where) {
        into.clear();
        if (sec == null) return;
        for (String k : sec.getKeys(false)) {
            Material m = Material.matchMaterial(k);
            if (m == null) getLogger().warning("Unknown material in " + where + ": " + k);
            else into.put(m, sec.getInt(k));
        }
    }

    private void loadTiers() {
        tiers.clear();
        ConfigurationSection ts = getConfig().getConfigurationSection("tiers");
        if (ts == null) { getLogger().severe("config.yml has no 'tiers' section"); return; }
        for (String k : ts.getKeys(false)) {
            ConfigurationSection s = ts.getConfigurationSection(k);
            if (s == null) continue;

            Map<PotionEffectType, Integer> fx = new LinkedHashMap<>();
            ConfigurationSection es = s.getConfigurationSection("effects");
            if (es != null) for (String en : es.getKeys(false)) {
                PotionEffectType t = Registry.EFFECT.get(NamespacedKey.minecraft(en.toLowerCase(Locale.ROOT)));
                if (t == null) getLogger().warning("Unknown effect '" + en + "' in tier " + k);
                else fx.put(t, es.getInt(en));
            }

            List<Drop> table = new ArrayList<>();
            double total = 0;
            ConfigurationSection bd = s.getConfigurationSection("bonus-drops");
            ConfigurationSection bt = bd == null ? null : bd.getConfigurationSection("table");
            if (bt != null) for (String mn : bt.getKeys(false)) {
                Material m = Material.matchMaterial(mn);
                double w = bt.getDouble(mn);
                if (m == null || w <= 0) { getLogger().warning("Bad bonus drop '" + mn + "' in tier " + k); continue; }
                table.add(new Drop(m, w));
                total += w;
            }

            tiers.add(new Tier(
                    s.getInt("threshold"), s.getString("color", "#FFFFFF"), s.getInt("max-durability", 55),
                    s.getDouble("armor", 1.0), s.getDouble("toughness", 0.0), s.getInt("unbreaking", 0),
                    s.getBoolean("glint", false), s.getBoolean("snow-trail", false),
                    bd == null ? 0 : bd.getDouble("chance", 0), bd == null ? 1 : bd.getInt("amount", 1),
                    table, total, fx));
        }
        tiers.sort(Comparator.comparingInt(Tier::threshold));
    }

    private void registerRecipe() {
        Bukkit.removeRecipe(RECIPE);
        ConfigurationSection rs = getConfig().getConfigurationSection("recipe");
        if (rs == null || !rs.getBoolean("enabled", true) || tiers.isEmpty()) return;
        try {
            ShapedRecipe r = new ShapedRecipe(RECIPE, newHat(0));
            r.shape(rs.getStringList("shape").toArray(new String[0]));
            ConfigurationSection ing = rs.getConfigurationSection("ingredients");
            if (ing == null) throw new IllegalStateException("no ingredients");
            for (String key : ing.getKeys(false)) {
                Material m = Material.matchMaterial(ing.getString(key, ""));
                if (m == null) throw new IllegalStateException("unknown material for " + key);
                r.setIngredient(key.charAt(0), m);
            }
            Bukkit.addRecipe(r);
        } catch (Exception ex) {
            getLogger().warning("Santa hat recipe not registered: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ core helpers

    private int get(Player p) {
        return p.getPersistentDataContainer().getOrDefault(JOLLY, PersistentDataType.INTEGER, 0);
    }

    private int tierIdx(int j) {
        int t = 0;
        for (int i = 0; i < tiers.size(); i++) if (j >= tiers.get(i).threshold()) t = i;
        return t;
    }

    private boolean isHat(ItemStack i) {
        return i != null && i.getType() == Material.LEATHER_HELMET && i.hasItemMeta()
                && i.getItemMeta().getPersistentDataContainer().has(HAT, PersistentDataType.BYTE);
    }

    private int hatTier(ItemStack i) {
        if (!isHat(i)) return -1;
        return i.getItemMeta().getPersistentDataContainer().getOrDefault(HAT_TIER, PersistentDataType.INTEGER, -1);
    }

    private int hatVersion(ItemStack i) {
        return i.getItemMeta().getPersistentDataContainer().getOrDefault(HAT_VER, PersistentDataType.INTEGER, 0);
    }

    private boolean wearing(Player p) { return isHat(p.getInventory().getHelmet()); }

    private static String fmt(int n) { return String.format(Locale.US, "%,d", n); }

    private TagResolver tierColor(int t) {
        TextColor c = null;
        if (!tiers.isEmpty()) c = TextColor.fromHexString(tiers.get(Math.max(0, Math.min(t, tiers.size() - 1))).color());
        return TagResolver.resolver("tiercolor", Tag.styling(c == null ? NamedTextColor.WHITE : c));
    }

    /** Sets jolliness. Returns true if this moved the player UP a tier. */
    private boolean set(Player p, int v) {
        v = Math.max(0, Math.min(maxJolly, v));
        int oldT = tierIdx(get(p));
        p.getPersistentDataContainer().set(JOLLY, PersistentDataType.INTEGER, v);
        int newT = tierIdx(v);
        boolean up = false;
        if (newT != oldT) {
            refreshAllHats(p);
            if (newT > oldT) { up = true; announceTier(p, newT); }
        }
        updateTab(p);
        return up;
    }

    private void announceTier(Player p, int t) {
        if (announceOn) {
            Bukkit.broadcast(MM.deserialize(announceMsg,
                    Placeholder.unparsed("player", p.getName()),
                    Placeholder.unparsed("tier", String.valueOf(t + 1)),
                    tierColor(t)));
        }
        if (tierSoundOn) {
            List<Player> targets = tierSoundAll ? new ArrayList<>(Bukkit.getOnlinePlayers()) : List.of(p);
            for (Player q : targets) q.playSound(q.getLocation(), tierSound, SoundCategory.PLAYERS, tierVol, tierPitch);
        }
    }

    private void gainSound(Player p) {
        if (!gainSoundOn) return;
        int now = Bukkit.getCurrentTick();
        Integer last = lastGainSound.get(p.getUniqueId());
        if (last != null && now - last < gainGap) return;
        lastGainSound.put(p.getUniqueId(), now);
        p.playSound(p.getLocation(), gainSound, SoundCategory.PLAYERS, gainVol, gainPitch);
    }

    /** True if this player may earn right now: hat on, not (ignored) creative. */
    private boolean canEarn(Player p) {
        if (tiers.isEmpty() || !wearing(p)) return false;
        return !(ignoreCreative && p.getGameMode() == GameMode.CREATIVE);
    }

    /** Points only count while the hat is on (and not creative, if configured). */
    private void add(Player p, int n) {
        if (n <= 0 || !canEarn(p)) return;
        boolean tieredUp = set(p, get(p) + n);
        if (!tieredUp) gainSound(p);
    }

    private boolean cooling(UUID id, String key, long ms) {
        long now = System.currentTimeMillis();
        String k = id + key;
        Long last = cooldowns.get(k);
        if (last != null && now - last < ms) return true;
        cooldowns.put(k, now);
        return false;
    }

    private void updateTab(Player p) {
        if (!tabEnabled) { p.playerListName(null); return; }
        int t = tierIdx(get(p));
        p.playerListName(MM.deserialize(tabFormat,
                Placeholder.unparsed("name", p.getName()),
                Placeholder.unparsed("jolly", fmt(get(p))),
                Placeholder.unparsed("tier", String.valueOf(t + 1)),
                tierColor(t)));
    }

    // ------------------------------------------------------------------ the hat item

    private ItemStack newHat(int tier) {
        ItemStack hat = new ItemStack(Material.LEATHER_HELMET);
        hat.editMeta(m -> {
            m.getPersistentDataContainer().set(HAT, PersistentDataType.BYTE, (byte) 1);
            if (m instanceof LeatherArmorMeta lm) lm.setColor(Color.RED); // used when hat-display is off
        });
        applyTier(hat, tier);
        return hat;
    }

    private void applyTier(ItemStack hat, int t) {
        if (tiers.isEmpty()) return;
        t = Math.max(0, Math.min(tiers.size() - 1, t));
        final Tier T = tiers.get(t);
        final int tierNo = t + 1;
        final int total = tiers.size();
        final NamespacedKey model = new NamespacedKey("jolly", "santa_hat_" + tierNo);
        hat.editMeta(m -> {
            m.getPersistentDataContainer().set(HAT_TIER, PersistentDataType.INTEGER, tierNo - 1);
            m.getPersistentDataContainer().set(HAT_VER, PersistentDataType.INTEGER, HAT_VERSION);
            m.displayName(Component.text("Santa Hat", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
            TextColor c = TextColor.fromHexString(T.color());
            m.lore(List.of(Component.text("Jolliness tier " + tierNo + "/" + total, c == null ? NamedTextColor.GOLD : c)
                    .decoration(TextDecoration.ITALIC, false)));

            if (m instanceof Damageable d) {
                d.setMaxDamage(T.maxDura());
                if (d.getDamage() >= T.maxDura()) d.setDamage(T.maxDura() - 1);
            }

            // Custom attribute modifiers REPLACE the item's defaults, so these are the helmet's totals.
            m.removeAttributeModifier(Attribute.ARMOR);
            m.removeAttributeModifier(Attribute.ARMOR_TOUGHNESS);
            if (T.armor() > 0) m.addAttributeModifier(Attribute.ARMOR, new AttributeModifier(
                    M_ARMOR, T.armor(), AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.HEAD));
            if (T.tough() > 0) m.addAttributeModifier(Attribute.ARMOR_TOUGHNESS, new AttributeModifier(
                    M_TOUGH, T.tough(), AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.HEAD));

            m.removeEnchant(Enchantment.UNBREAKING);
            if (T.unbreaking() > 0) m.addEnchant(Enchantment.UNBREAKING, T.unbreaking(), true);

            // Unbreaking would make every tier glint, so glint is forced to match the config.
            m.setEnchantmentGlintOverride(T.glint());

            var eq = m.getEquippable();
            eq.setSlot(EquipmentSlot.HEAD);
            if (displayEnabled) {
                // The 3D hat IS the item model. With no armor-layer asset on the equippable, the client
                // draws the item's own model on the head (like a carved pumpkin) - tinted per tier by the pack.
                m.setItemModel(model);
                eq.setModel(null);
            } else {
                m.setItemModel(null);
                eq.setModel(NamespacedKey.minecraft("leather"));
            }
            m.setEquippable(eq);
        });
    }

    /** Brings every hat in the player's inventory to their current tier (and the current hat format). */
    private void refreshAllHats(Player p) {
        if (tiers.isEmpty()) return;
        int t = tierIdx(get(p));
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!isHat(s)) continue;
            if (hatTier(s) == t && hatVersion(s) == HAT_VERSION) continue;
            applyTier(s, t);
            inv.setItem(i, s);
        }
    }

    private boolean hasAnyHat(Player p) {
        for (ItemStack s : p.getInventory().getContents()) if (isHat(s)) return true;
        return false;
    }

    @EventHandler public void onPrepareCraft(PrepareItemCraftEvent e) {
        if (!(e.getRecipe() instanceof Keyed k) || !RECIPE.equals(k.getKey())) return;
        if (e.getViewers().isEmpty() || !(e.getViewers().get(0) instanceof Player p)) return;
        e.getInventory().setResult(newHat(tierIdx(get(p)))); // crafted hats start at your current tier
    }

    // ------------------------------------------------------------------ tick loops

    private Map<PotionEffectType, Integer> effectsFor(int t) {
        Map<PotionEffectType, Integer> out = new HashMap<>();
        for (int i = 0; i <= t; i++) tiers.get(i).effects().forEach((k, v) -> out.merge(k, v, Math::max));
        return out;
    }

    private boolean snowTrail(int t) {
        for (int i = 0; i <= t; i++) if (tiers.get(i).snow()) return true;
        return false;
    }

    /** Once a second: buffs, hat sync, actionbar, playtime. */
    private void slowTick() {
        if (tiers.isEmpty()) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            refreshAllHats(p);
            if (!wearing(p)) continue;
            int j = get(p);
            int t = tierIdx(j);
            effectsFor(t).forEach((type, amp) ->
                    p.addPotionEffect(new PotionEffect(type, 60, amp, true, false, true)));
            if (barEnabled) {
                String next = t + 1 < tiers.size() ? fmt(tiers.get(t + 1).threshold()) : "MAX";
                p.sendActionBar(MM.deserialize(barFormat,
                        Placeholder.unparsed("jolly", fmt(j)),
                        Placeholder.unparsed("tier", String.valueOf(t + 1)),
                        Placeholder.unparsed("next", next),
                        tierColor(t)));
            }
            if (playOn) {
                int s = playSecs.merge(p.getUniqueId(), 1, Integer::sum);
                if (s >= playMinutes * 60) { playSecs.put(p.getUniqueId(), 0); add(p, playPts); }
            }
        }
    }

    /** Every 2 ticks: the snow trail. */
    private void fastTick() {
        if (tiers.isEmpty()) return;
        int now = Bukkit.getCurrentTick();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (now - lastMove.getOrDefault(p.getUniqueId(), -100) > 6) continue;
            if (!wearing(p) || !snowTrail(tierIdx(get(p)))) continue;
            p.getWorld().spawnParticle(Particle.SNOWFLAKE, p.getLocation().add(0, 0.15, 0), 3, 0.2, 0.05, 0.2, 0.01);
        }
    }

    // ------------------------------------------------------------------ gains

    @EventHandler public void onMove(PlayerMoveEvent e) {
        if (e.getTo() == null) return;
        double dx = e.getTo().getX() - e.getFrom().getX(), dz = e.getTo().getZ() - e.getFrom().getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d < 0.01) return;
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        lastMove.put(id, Bukkit.getCurrentTick());
        if (!walkEnabled || d > 2 || p.isInsideVehicle() || p.isGliding() || !wearing(p)) return;

        double dist = walkDist.merge(id, d, Double::sum);
        if (dist < 1) return;
        double whole = Math.floor(dist);
        walkDist.put(id, dist - whole);

        String biome = p.getLocation().getBlock().getBiome().getKey().getKey();
        double mult = snowBiomes.contains(biome) ? snowMult : 1.0;
        double acc = walkPts.merge(id, whole / walkBlocks * walkPoints * mult, Double::sum);
        int pts = (int) acc;
        if (pts > 0) { walkPts.put(id, acc - pts); add(p, pts); }
    }

    private boolean isOre(Material m) { return m.name().endsWith("_ORE") || m == Material.ANCIENT_DEBRIS; }

    private int pack(Block b) { return ((b.getY() + 2048) << 8) | ((b.getX() & 15) << 4) | (b.getZ() & 15); }

    private void markPlaced(Block b) {
        PersistentDataContainer pdc = b.getChunk().getPersistentDataContainer();
        int[] old = pdc.getOrDefault(PLACED, PersistentDataType.INTEGER_ARRAY, new int[0]);
        int k = pack(b);
        for (int v : old) if (v == k) return;
        int[] n = Arrays.copyOf(old, old.length + 1);
        n[old.length] = k;
        pdc.set(PLACED, PersistentDataType.INTEGER_ARRAY, n);
    }

    private boolean consumePlaced(Block b) {
        PersistentDataContainer pdc = b.getChunk().getPersistentDataContainer();
        int[] old = pdc.get(PLACED, PersistentDataType.INTEGER_ARRAY);
        if (old == null) return false;
        int k = pack(b), idx = -1;
        for (int i = 0; i < old.length; i++) if (old[i] == k) { idx = i; break; }
        if (idx < 0) return false;
        if (old.length == 1) { pdc.remove(PLACED); return true; }
        int[] n = new int[old.length - 1];
        System.arraycopy(old, 0, n, 0, idx);
        System.arraycopy(old, idx + 1, n, idx, old.length - idx - 1);
        pdc.set(PLACED, PersistentDataType.INTEGER_ARRAY, n);
        return true;
    }

    /** Remembers that this spot already paid out. Returns false if it had paid before. */
    private boolean markAwarded(Block b) {
        PersistentDataContainer pdc = b.getChunk().getPersistentDataContainer();
        int[] old = pdc.getOrDefault(AWARDED, PersistentDataType.INTEGER_ARRAY, new int[0]);
        int k = pack(b);
        for (int v : old) if (v == k) return false;
        int[] n = Arrays.copyOf(old, old.length + 1);
        n[old.length] = k;
        pdc.set(AWARDED, PersistentDataType.INTEGER_ARRAY, n);
        return true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        Material m = b.getType();
        if (isOre(m)) markPlaced(b);

        int pts = 0;
        if (rareOn) pts += rarePoints.getOrDefault(m, 0);
        if (decorOn) pts += decorPoints.getOrDefault(m, 0);
        if (pts <= 0) return;
        Player p = e.getPlayer();
        if (!canEarn(p)) return;
        if (rareOnce && !markAwarded(b)) return;
        add(p, pts);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        Material type = b.getType();
        if (!isOre(type) || consumePlaced(b)) return; // player-placed ore earns nothing
        Player p = e.getPlayer();
        if (!canEarn(p)) return;

        if (mineEnabled) add(p, minePoints.getOrDefault(type, 0));
        rollBonus(p, b);
    }

    private void rollBonus(Player p, Block b) {
        Tier T = tiers.get(tierIdx(get(p)));
        if (T.bonusChance() <= 0 || T.table().isEmpty()) return;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (rnd.nextDouble() >= T.bonusChance()) return;
        double r = rnd.nextDouble() * T.totalWeight();
        Drop pick = T.table().get(T.table().size() - 1);
        for (Drop d : T.table()) { r -= d.weight(); if (r < 0) { pick = d; break; } }
        b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), new ItemStack(pick.mat(), T.bonusAmount()));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.4f);
        b.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, b.getLocation().add(0.5, 0.5, 0.5), 6, 0.3, 0.3, 0.3, 0);
    }

    @EventHandler public void onChat(AsyncChatEvent e) {
        if (!chatEnabled) return;
        String msg = PlainTextComponentSerializer.plainText().serialize(e.message()).toLowerCase(Locale.ROOT);
        if (chatPhrases.stream().noneMatch(msg::contains)) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> { // back on the main thread
            if (cooling(p.getUniqueId(), "chat", chatCdMs)) return;
            add(p, chatPoints);
        });
    }

    @EventHandler public void onPickup(EntityPickupItemEvent e) {
        if (!giftEnabled || !(e.getEntity() instanceof Player taker)) return;
        UUID thrower = e.getItem().getThrower();
        if (thrower == null || thrower.equals(taker.getUniqueId())) return;
        Player giver = Bukkit.getPlayer(thrower);
        if (giver == null) return;
        if (cooling(thrower, "gift>" + taker.getUniqueId(), giftCdMs)) return;
        add(giver, giftPoints);
    }

    @EventHandler public void onBreed(EntityBreedEvent e) {
        if (!breedOn || !(e.getBreeder() instanceof Player p)) return;
        if (cooling(p.getUniqueId(), "breed", breedCd)) return;
        add(p, breedPts);
    }

    @EventHandler public void onSnowball(ProjectileHitEvent e) {
        if (!snowballOn || !(e.getEntity() instanceof Snowball sb)) return;
        if (!(sb.getShooter() instanceof Player p)) return;
        if (!(e.getHitEntity() instanceof LivingEntity hit) || hit.equals(p)) return;
        if (cooling(p.getUniqueId(), "snowball", snowballCd)) return;
        add(p, snowballPts);
    }

    @EventHandler public void onTrade(PlayerTradeEvent e) {
        if (!tradeOn) return;
        Player p = e.getPlayer();
        if (cooling(p.getUniqueId(), "trade", tradeCd)) return;
        add(p, tradePts);
    }

    @EventHandler public void onEat(PlayerItemConsumeEvent e) {
        if (!foodOn) return;
        int pts = foodPoints.getOrDefault(e.getItem().getType(), 0);
        if (pts <= 0) return;
        Player p = e.getPlayer();
        if (cooling(p.getUniqueId(), "food", foodCd)) return;
        add(p, pts);
    }

    @EventHandler public void onFish(PlayerFishEvent e) {
        if (!fishOn || e.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        add(e.getPlayer(), fishPts);
    }

    @EventHandler public void onAdvancement(PlayerAdvancementDoneEvent e) {
        if (!advOn || e.getAdvancement().getDisplay() == null) return; // recipes have no display
        add(e.getPlayer(), advPts);
    }

    @EventHandler public void onKill(EntityDeathEvent e) {
        if (!killOn) return;
        Player killer = e.getEntity().getKiller();
        if (killer == null) return;
        Integer boss = bossPoints.get(e.getEntityType());
        if (boss != null) add(killer, boss);
        else if (e.getEntity() instanceof Monster) add(killer, killPts);
    }

    // ------------------------------------------------------------------ death / join / quit

    @EventHandler public void onDeath(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        boolean had = e.getDrops().stream().anyMatch(this::isHat);
        if (destroyHat) e.getDrops().removeIf(this::isHat);
        if (hatOnRespawn && had) needsHat.add(p.getUniqueId());
        if (!keepJolly) p.getPersistentDataContainer().set(JOLLY, PersistentDataType.INTEGER, 0);
    }

    @EventHandler public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> {
            if (needsHat.remove(p.getUniqueId()) && !hasAnyHat(p)) {
                ItemStack hat = newHat(tierIdx(get(p)));
                if (p.getInventory().getHelmet() == null) p.getInventory().setHelmet(hat);
                else p.getInventory().addItem(hat);
            }
            refreshAllHats(p);
            updateTab(p);
        });
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (recipeDiscover) p.discoverRecipe(RECIPE);
        if (giveFirstJoin && !p.getPersistentDataContainer().has(GIVEN, PersistentDataType.BYTE)) {
            p.getPersistentDataContainer().set(GIVEN, PersistentDataType.BYTE, (byte) 1);
            p.getInventory().addItem(newHat(tierIdx(get(p))));
        }
        updateTab(p);
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        walkDist.remove(id); walkPts.remove(id); lastMove.remove(id);
        lastGainSound.remove(id); playSecs.remove(id);
        String prefix = id.toString();
        cooldowns.keySet().removeIf(k -> k.startsWith(prefix));
    }

    // ------------------------------------------------------------------ /jolly

    @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 0) {
            if (!(s instanceof Player p)) { s.sendMessage("Players only. Try /jolly get <player>."); return true; }
            int j = get(p), t = tierIdx(j);
            String next = t + 1 < tiers.size() ? "next tier at " + fmt(tiers.get(t + 1).threshold()) : "max tier";
            p.sendMessage(Component.text("Jolliness: " + fmt(j) + " (tier " + (t + 1) + ", " + next + ")", NamedTextColor.GOLD));
            if (!wearing(p)) p.sendMessage(Component.text("Wear your Santa Hat to earn more.", NamedTextColor.GRAY));
            return true;
        }
        if (!s.hasPermission("jolly.admin")) { s.sendMessage("No permission."); return true; }

        String sub = a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) { loadSettings(); s.sendMessage("Jolly config reloaded."); return true; }
        if (a.length < 2) return false;
        Player t = Bukkit.getPlayer(a[1]);
        if (t == null) { s.sendMessage("Player not found."); return true; }

        switch (sub) {
            case "get" -> s.sendMessage(t.getName() + " jolliness = " + fmt(get(t)));
            case "give" -> { t.getInventory().addItem(newHat(tierIdx(get(t)))); s.sendMessage("Gave a Santa Hat to " + t.getName()); }
            case "add", "set" -> {
                if (a.length < 3) return false;
                int n;
                try { n = Integer.parseInt(a[2]); } catch (NumberFormatException ex) { return false; }
                set(t, sub.equals("add") ? get(t) + n : n);
                s.sendMessage(t.getName() + " jolliness = " + fmt(get(t)));
            }
            default -> { return false; }
        }
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (!s.hasPermission("jolly.admin")) return List.of();
        if (a.length == 1) return List.of("add", "set", "get", "give", "reload").stream()
                .filter(x -> x.startsWith(a[0].toLowerCase(Locale.ROOT))).toList();
        if (a.length == 2 && !a[0].equalsIgnoreCase("reload")) return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName).filter(n -> n.toLowerCase(Locale.ROOT).startsWith(a[1].toLowerCase(Locale.ROOT))).toList();
        if (a.length == 3 && (a[0].equalsIgnoreCase("add") || a[0].equalsIgnoreCase("set"))) return List.of("10", "100", "1000");
        return List.of();
    }
}
