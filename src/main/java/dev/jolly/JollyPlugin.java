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
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BellRingEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
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
import org.bukkit.util.Vector;

import java.io.File;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class JollyPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final int CONFIG_VERSION = 6;
    /** Bump when the hat item's components change so old hats get rewritten. */
    private static final int HAT_VERSION = 4;

    record Tier(String name, int threshold, String color, int maxDura, double armor, double tough, int unbreaking,
                boolean glint, double dupChance, Map<PotionEffectType, Integer> effects) {}

    /** A selectable particle style. Behaviour lives in renderStyle(); the unlock tier comes from config. */
    record Style(String id, String name, int defaultTier) {}

    /** A player-vs-player kill worked out from the victim's last damage. */
    record PvpKill(Player killer, String method, Component weapon) {}

    private static final List<Style> STYLES = List.of(
            new Style("snow", "Snow Trail", 2),
            new Style("snow_glow", "Snow Glow", 3),
            new Style("candy_cane", "Candy Cane", 3),
            new Style("holly", "Holly", 4),
            new Style("snow_aura", "Snow Aura", 4),
            new Style("starlight", "Starlight", 5),
            new Style("aurora", "Aurora Halo", 5),
            new Style("crown", "Frost Crown", 6));

    private static final Particle.DustOptions DUST_RED = new Particle.DustOptions(Color.fromRGB(225, 35, 35), 1.1f);
    private static final Particle.DustOptions DUST_WHITE = new Particle.DustOptions(Color.fromRGB(250, 250, 250), 1.1f);
    private static final Particle.DustOptions DUST_GREEN = new Particle.DustOptions(Color.fromRGB(35, 190, 60), 1.1f);
    private static final Color[] AURORA = {
            Color.fromRGB(80, 255, 150), Color.fromRGB(60, 220, 255),
            Color.fromRGB(100, 120, 255), Color.fromRGB(190, 90, 255)};
    private static final float[] JINGLE_PITCHES = {1.2f, 1.5f, 1.8f, 2.0f};

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final List<Tier> tiers = new ArrayList<>();
    private NamespacedKey JOLLY, HAT, HAT_TIER, HAT_VER, GIVEN, PLACED, AWARDED, RECIPE, M_ARMOR, M_TOUGH, PARTICLE, JINGLE, DISCS;

    // runtime state
    private final Map<UUID, Double> walkDist = new HashMap<>();
    private final Map<UUID, Double> walkPts = new HashMap<>();
    private final Map<UUID, Integer> lastMove = new HashMap<>();
    private final Map<UUID, Integer> lastGainSound = new HashMap<>();
    private final Map<UUID, Integer> playSecs = new HashMap<>();
    private final Map<String, Long> cooldowns = new HashMap<>();
    private final Set<UUID> needsHat = new HashSet<>();
    private final Map<UUID, Integer> jingleTick = new HashMap<>();
    private final Map<UUID, Integer> passiveSecs = new HashMap<>();
    private final Map<UUID, Integer> pumpkinUse = new HashMap<>();
    private final Map<Snowball, Location> candyBalls = new HashMap<>();

    // cached config
    private int maxJolly;
    private boolean ignoreCreative, giveFirstJoin, recipeDiscover, requireHat;
    private boolean keepJolly, destroyHat, hatOnRespawn;
    private boolean tabEnabled, displayEnabled, chatPrefixOn;
    private String tabFormat, chatPrefixFormat;
    /** Tier index per online player, so the async chat thread never has to touch player data. */
    private final Map<UUID, Integer> tierCache = new java.util.concurrent.ConcurrentHashMap<>();

    private final Set<Material> dupExclude = EnumSet.noneOf(Material.class);
    private final Map<String, Integer> styleTier = new LinkedHashMap<>();
    private String defaultStyle = "snow";
    private boolean jingleOn, jingleDefault, burstOn, cheerOn;
    private int jingleMinTier, cheerRadius;
    private String jingleSound, cheerMsg;
    private float jingleVol;
    private long cheerCdMs;

    private boolean passiveOn, iceBoatOn, snowmanOn, bellOn, discOn, pvpOn, candyOn, knockOn;
    private int passiveSecsNeeded, passivePts, snowmanPts, bellPts, pvpPenalty, candyTier, knockTier;
    private double iceMult, knockStrength, knockLift;
    private long snowmanCd, bellCd;
    private String pvpMessage;
    private final Set<Material> iceBlocks = EnumSet.noneOf(Material.class);
    private final Map<Material, Integer> discPoints = new EnumMap<>(Material.class);
    private final Map<String, String> pvpVerbs = new HashMap<>();

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
        PARTICLE = new NamespacedKey(this, "particle");
        JINGLE = new NamespacedKey(this, "jingle");
        DISCS = new NamespacedKey(this, "discs");

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
        requireHat = c.getBoolean("require-hat", false);
        giveFirstJoin = c.getBoolean("give-hat-on-first-join", false);
        recipeDiscover = c.getBoolean("recipe.discover-on-join", true);

        keepJolly = c.getBoolean("death.keep-jolliness", true);
        destroyHat = c.getBoolean("death.destroy-hat", false);
        hatOnRespawn = c.getBoolean("death.give-hat-on-respawn", false);

        tabEnabled = c.getBoolean("tablist.enabled", true);
        tabFormat = c.getString("tablist.format", "<tiercolor>[<tier>]</tiercolor> <name>");
        chatPrefixOn = c.getBoolean("chat-prefix.enabled", true);
        chatPrefixFormat = c.getString("chat-prefix.format", "<tiercolor>[<tier>]</tiercolor> ");
        displayEnabled = c.getBoolean("hat-display.enabled", true);

        dupExclude.clear();
        List<String> ex = c.contains("ore-duplication.exclude")
                ? c.getStringList("ore-duplication.exclude") : List.of("ANCIENT_DEBRIS", "NETHERITE_SCRAP");
        for (String n : ex) {
            Material m = Material.matchMaterial(n);
            if (m == null) getLogger().warning("Unknown material in ore-duplication.exclude: " + n);
            else dupExclude.add(m);
        }

        styleTier.clear();
        for (Style st : STYLES) styleTier.put(st.id(), c.getInt("particles.unlock-tiers." + st.id(), st.defaultTier()));
        defaultStyle = c.getString("particles.default", "snow").toLowerCase(Locale.ROOT);
        jingleOn = c.getBoolean("jingle.enabled", true);
        jingleMinTier = c.getInt("jingle.unlock-tier", 3);
        jingleDefault = c.getBoolean("jingle.default-on", false);
        jingleSound = c.getString("jingle.sound", "minecraft:block.note_block.chime");
        jingleVol = (float) c.getDouble("jingle.volume", 0.25);
        cheerOn = c.getBoolean("cheer.enabled", true);
        cheerCdMs = c.getLong("cheer.cooldown-seconds", 30) * 1000L;
        cheerRadius = c.getInt("cheer.radius", 24);
        cheerMsg = c.getString("cheer.message", "<tiercolor><player> cheers!</tiercolor>");
        burstOn = c.getBoolean("tier-up-burst.enabled", true);

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
        announceMsg = c.getString("announce.message", "<gold><player> reached the <tiercolor><tier></tiercolor> tier!</gold>");

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

        passiveOn = c.getBoolean("gains.passive.enabled", true);
        passiveSecsNeeded = Math.max(1, c.getInt("gains.passive.seconds", 60));
        passivePts = c.getInt("gains.passive.points", 1);
        iceBoatOn = c.getBoolean("gains.ice-boating.enabled", true);
        iceMult = c.getDouble("gains.ice-boating.multiplier", 2.0);
        iceBlocks.clear();
        for (String n : c.getStringList("gains.ice-boating.blocks")) {
            Material m = Material.matchMaterial(n);
            if (m == null) getLogger().warning("Unknown material in ice-boating.blocks: " + n);
            else iceBlocks.add(m);
        }
        snowmanOn = c.getBoolean("gains.snowmen.enabled", true);
        snowmanPts = c.getInt("gains.snowmen.points", 20);
        snowmanCd = c.getLong("gains.snowmen.cooldown-seconds", 60) * 1000L;
        bellOn = c.getBoolean("gains.bells.enabled", true);
        bellPts = c.getInt("gains.bells.points", 5);
        bellCd = c.getLong("gains.bells.cooldown-seconds", 60) * 1000L;
        discOn = c.getBoolean("gains.music-discs.enabled", true);
        readMaterials(c.getConfigurationSection("gains.music-discs.points"), discPoints, "music-discs.points");

        pvpOn = c.getBoolean("death.pvp.enabled", true);
        pvpPenalty = Math.max(0, c.getInt("death.pvp.penalty", 250));
        pvpMessage = c.getString("death.pvp.message",
                "<red><killer> <method> <victim> using <weapon> and lost <points> jolliness points for being naughty.</red>");
        pvpVerbs.put("melee", c.getString("death.pvp.verbs.melee", "slew"));
        pvpVerbs.put("projectile", c.getString("death.pvp.verbs.projectile", "shot"));
        pvpVerbs.put("explosion", c.getString("death.pvp.verbs.explosion", "blew up"));

        candyOn = c.getBoolean("snowballs.candy-cane.enabled", true);
        candyTier = c.getInt("snowballs.candy-cane.unlock-tier", 2);
        knockOn = c.getBoolean("snowballs.knockback.enabled", true);
        knockTier = c.getInt("snowballs.knockback.unlock-tier", 3);
        knockStrength = c.getDouble("snowballs.knockback.strength", 0.5);
        knockLift = c.getDouble("snowballs.knockback.lift", 0.25);
        candyBalls.clear();

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

            tiers.add(new Tier(
                    k, s.getInt("threshold"), s.getString("color", "#FFFFFF"), s.getInt("max-durability", 55),
                    s.getDouble("armor", 1.0), s.getDouble("toughness", 0.0), s.getInt("unbreaking", 0),
                    s.getBoolean("glint", false),
                    Math.max(0, Math.min(1, s.getDouble("ore-duplicate-chance", 0))), fx));
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

    /** Display name of tier index t (0-based), e.g. "Rudolph". */
    private String tierName(int t) {
        if (tiers.isEmpty()) return "Tier " + (t + 1);
        return tiers.get(Math.max(0, Math.min(t, tiers.size() - 1))).name();
    }

    /** Display name for a 1-based tier number from the config (unlock tiers). */
    private String tierNameNo(int n) { return tierName(n - 1); }

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
                    Placeholder.unparsed("tier", tierName(t)),
                    Placeholder.unparsed("tiernumber", String.valueOf(t + 1)),
                    tierColor(t)));
        }
        if (tierSoundOn) {
            List<Player> targets = tierSoundAll ? new ArrayList<>(Bukkit.getOnlinePlayers()) : List.of(p);
            for (Player q : targets) q.playSound(q.getLocation(), tierSound, SoundCategory.PLAYERS, tierVol, tierPitch);
        }
        if (burstOn) burst(p.getLocation(), t, true);
    }

    private void gainSound(Player p) {
        if (!gainSoundOn) return;
        int now = Bukkit.getCurrentTick();
        Integer last = lastGainSound.get(p.getUniqueId());
        if (last != null && now - last < gainGap) return;
        lastGainSound.put(p.getUniqueId(), now);
        p.playSound(p.getLocation(), gainSound, SoundCategory.PLAYERS, gainVol, gainPitch);
    }

    /** True if this player may earn right now: hat on (if require-hat), not (ignored) creative. */
    private boolean canEarn(Player p) {
        if (tiers.isEmpty() || (requireHat && !wearing(p))) return false;
        return !(ignoreCreative && p.getGameMode() == GameMode.CREATIVE);
    }

    /** Points count with or without the hat (see require-hat), and not in creative if configured. */
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
        int t = tierIdx(get(p));
        tierCache.put(p.getUniqueId(), t);
        if (!tabEnabled) { p.playerListName(null); return; }
        p.playerListName(MM.deserialize(tabFormat,
                Placeholder.unparsed("name", p.getName()),
                Placeholder.unparsed("jolly", fmt(get(p))),
                Placeholder.unparsed("tier", tierName(t)),
                Placeholder.unparsed("tiernumber", String.valueOf(t + 1)),
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
        final NamespacedKey model = new NamespacedKey("jolly", "santa_hat_" + tierNo);
        hat.editMeta(m -> {
            m.getPersistentDataContainer().set(HAT_TIER, PersistentDataType.INTEGER, tierNo - 1);
            m.getPersistentDataContainer().set(HAT_VER, PersistentDataType.INTEGER, HAT_VERSION);
            m.displayName(Component.text("Santa Hat", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
            TextColor c = TextColor.fromHexString(T.color());
            m.lore(List.of(Component.text(T.name(), c == null ? NamedTextColor.GOLD : c)
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

    /** Once a second: buffs, hat sync, playtime. */
    private void slowTick() {
        if (tiers.isEmpty()) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            refreshAllHats(p);
            if (playOn && canEarn(p)) {
                int s = playSecs.merge(p.getUniqueId(), 1, Integer::sum);
                if (s >= playMinutes * 60) { playSecs.put(p.getUniqueId(), 0); add(p, playPts); }
            }
            if (passiveOn && canEarn(p)) {
                int ps = passiveSecs.merge(p.getUniqueId(), 1, Integer::sum);
                if (ps >= passiveSecsNeeded) { passiveSecs.put(p.getUniqueId(), 0); add(p, passivePts); }
            }
            if (!wearing(p)) continue; // buffs are hat-only
            int j = get(p);
            int t = tierIdx(j);
            effectsFor(t).forEach((type, amp) ->
                    p.addPotionEffect(new PotionEffect(type, 60, amp, true, false, true)));
        }
    }

    /** Every 2 ticks: particle style and footstep jingle (hat worn only). */
    private void fastTick() {
        if (tiers.isEmpty()) return;
        int now = Bukkit.getCurrentTick();
        candyTrails();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!wearing(p) || p.isInvisible() || p.getGameMode() == GameMode.SPECTATOR) continue;
            UUID id = p.getUniqueId();
            boolean moving = now - lastMove.getOrDefault(id, -100) <= 6;
            String style = selectedStyle(p);
            if (style != null) renderStyle(p, style, moving, now);
            if (moving) jingle(p, id);
        }
    }

    // ------------------------------------------------------------------ cosmetics

    /** Red and white dust along the path of every candy-cane snowball. */
    private void candyTrails() {
        if (candyBalls.isEmpty()) return;
        Iterator<Map.Entry<Snowball, Location>> it = candyBalls.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Snowball, Location> en = it.next();
            Snowball sb = en.getKey();
            if (!sb.isValid()) { it.remove(); continue; }
            Location cur = sb.getLocation(), last = en.getValue();
            World w = cur.getWorld();
            if (w != null && last.getWorld() == w) {
                Vector step = cur.toVector().subtract(last.toVector());
                for (int k = 0; k < 3; k++) {
                    Location l = last.clone().add(step.clone().multiply(k / 3.0));
                    w.spawnParticle(Particle.DUST, l, 1, 0.04, 0.04, 0.04, 0, DUST_RED);
                    w.spawnParticle(Particle.DUST, l, 1, 0.04, 0.04, 0.04, 0, DUST_WHITE);
                }
            }
            en.setValue(cur);
        }
    }

    private boolean unlocked(String style, Player p) {
        int need = styleTier.getOrDefault(style, 0);
        return need > 0 && tierIdx(get(p)) + 1 >= need;
    }

    /** The player's active style id, or null when off / locked / unknown. */
    private String selectedStyle(Player p) {
        String s = p.getPersistentDataContainer().get(PARTICLE, PersistentDataType.STRING);
        if (s == null) s = defaultStyle;
        if (s.equals("none") || !styleTier.containsKey(s) || !unlocked(s, p)) return null;
        return s;
    }

    private void renderStyle(Player p, String style, boolean moving, int now) {
        World w = p.getWorld();
        Location feet = p.getLocation();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        switch (style) {
            case "snow" -> {
                if (moving) w.spawnParticle(Particle.SNOWFLAKE, feet.clone().add(0, 0.15, 0), 3, 0.2, 0.05, 0.2, 0.01);
            }
            case "snow_glow" -> {
                if (moving) {
                    w.spawnParticle(Particle.SNOWFLAKE, feet.clone().add(0, 0.15, 0), 2, 0.2, 0.05, 0.2, 0.01);
                    w.spawnParticle(Particle.END_ROD, feet.clone().add(0, 0.2, 0), 1, 0.25, 0.05, 0.25, 0.005);
                } else if (r.nextInt(5) == 0) { // idle shimmer
                    Location l = feet.clone().add(r.nextDouble(-0.5, 0.5), r.nextDouble(0.2, 1.8), r.nextDouble(-0.5, 0.5));
                    w.spawnParticle(Particle.END_ROD, l, 0, 0, 0.4, 0, 0.01);
                }
            }
            case "candy_cane" -> {
                if (moving) {
                    Location l = feet.clone().add(0, 0.2, 0);
                    w.spawnParticle(Particle.DUST, l, 2, 0.2, 0.05, 0.2, 0, DUST_RED);
                    w.spawnParticle(Particle.DUST, l, 2, 0.2, 0.05, 0.2, 0, DUST_WHITE);
                }
            }
            case "holly" -> {
                if (moving) {
                    Location l = feet.clone().add(0, 0.2, 0);
                    w.spawnParticle(Particle.DUST, l, 2, 0.2, 0.05, 0.2, 0, DUST_GREEN);
                    w.spawnParticle(Particle.DUST, l, 1, 0.2, 0.05, 0.2, 0, DUST_RED);
                }
            }
            case "snow_aura" -> {
                double a = now * 0.22;
                for (int i = 0; i < 3; i++) {
                    double ang = a + i * (2 * Math.PI / 3);
                    Location l = feet.clone().add(Math.cos(ang) * 0.9, 1.0 + Math.sin(a * 0.5 + i) * 0.35, Math.sin(ang) * 0.9);
                    w.spawnParticle(Particle.SNOWFLAKE, l, 1, 0, 0, 0, 0);
                }
            }
            case "starlight" -> {
                for (int i = 0; i < 2; i++) {
                    Location l = feet.clone().add(r.nextDouble(-0.6, 0.6), r.nextDouble(0, 2.0), r.nextDouble(-0.6, 0.6));
                    w.spawnParticle(Particle.END_ROD, l, 0, 0, 0.6, 0, 0.02);
                }
            }
            case "aurora" -> {
                Location base = p.getEyeLocation().add(0, 0.55, 0);
                double a = now * 0.15;
                for (int i = 0; i < 6; i++) {
                    double ang = a + i * Math.PI / 3;
                    int k = (i + now / 10) % AURORA.length;
                    Particle.DustTransition dt = new Particle.DustTransition(AURORA[k], AURORA[(k + 1) % AURORA.length], 1.0f);
                    w.spawnParticle(Particle.DUST_COLOR_TRANSITION, base.clone().add(Math.cos(ang) * 0.55, 0, Math.sin(ang) * 0.55), 1, 0, 0, 0, 0, dt);
                }
            }
            case "crown" -> {
                Location base = p.getEyeLocation().add(0, 0.5, 0);
                for (int i = 0; i < 2; i++) {
                    int k = r.nextInt(5);
                    double ang = k * (2 * Math.PI / 5);
                    double h = k % 2 == 0 ? 0.25 : 0.1; // alternating crown points
                    w.spawnParticle(Particle.END_ROD, base.clone().add(Math.cos(ang) * 0.32, h, Math.sin(ang) * 0.32), 0, 0, 0, 0, 0);
                }
            }
            default -> { }
        }
    }

    private void jingle(Player p, UUID id) {
        if (!jingleOn || tierIdx(get(p)) + 1 < jingleMinTier || !jingleFor(p)) return;
        if (p.isSneaking() || p.isGliding() || p.isInsideVehicle() || p.isSwimming()) return;
        if (jingleTick.merge(id, 1, Integer::sum) < 5) return; // roughly every 10 ticks while moving
        jingleTick.put(id, 0);
        float pitch = JINGLE_PITCHES[ThreadLocalRandom.current().nextInt(JINGLE_PITCHES.length)];
        p.getWorld().playSound(p.getLocation(), jingleSound, SoundCategory.PLAYERS, jingleVol, pitch);
    }

    private boolean jingleFor(Player p) {
        Byte b = p.getPersistentDataContainer().get(JINGLE, PersistentDataType.BYTE);
        return b == null ? jingleDefault : b == 1;
    }

    /** Particle burst in the tier colour. Purely visual (no real fireworks, so nobody gets hurt). */
    private void burst(Location at, int t, boolean big) {
        World w = at.getWorld();
        if (w == null || tiers.isEmpty()) return;
        TextColor tc = TextColor.fromHexString(tiers.get(Math.max(0, Math.min(t, tiers.size() - 1))).color());
        Color col = tc == null ? Color.WHITE : Color.fromRGB(tc.red(), tc.green(), tc.blue());
        Location c = at.clone().add(0, 1.0, 0);
        int n = big ? 2 : 1;
        w.spawnParticle(Particle.DUST, c, 35 * n, 0.5, 0.7, 0.5, 0, new Particle.DustOptions(col, 1.4f));
        w.spawnParticle(Particle.SNOWFLAKE, c, 25 * n, 0.6, 0.8, 0.6, 0.05);
        w.spawnParticle(Particle.FIREWORK, c, 20 * n, 0.1, 0.1, 0.1, 0.2);
        w.playSound(c, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, SoundCategory.PLAYERS, big ? 1.0f : 0.7f, big ? 1.0f : 1.3f);
    }

    private void cheer(Player p) {
        if (!cheerOn) { p.sendMessage(Component.text("Cheering is disabled.", NamedTextColor.GRAY)); return; }
        if (!wearing(p)) { p.sendMessage(Component.text("Wear your Santa Hat to cheer.", NamedTextColor.GRAY)); return; }
        if (cooling(p.getUniqueId(), "cheer", cheerCdMs)) {
            p.sendMessage(Component.text("Catch your breath, then cheer again.", NamedTextColor.GRAY));
            return;
        }
        int t = tierIdx(get(p));
        burst(p.getLocation(), t, false);
        Component msg = MM.deserialize(cheerMsg, Placeholder.unparsed("player", p.getName()), tierColor(t));
        double r2 = (double) cheerRadius * cheerRadius;
        for (Player q : p.getWorld().getPlayers())
            if (q.getLocation().distanceSquared(p.getLocation()) <= r2) q.sendMessage(msg);
    }

    private void particleCommand(Player p, String[] a) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        int myTier = tierIdx(get(p)) + 1;
        if (a.length < 2) {
            String cur = selectedStyle(p);
            p.sendMessage(Component.text("Particle styles - /jolly particle <style|none|default>", NamedTextColor.GOLD));
            for (Style st : STYLES) {
                int need = styleTier.getOrDefault(st.id(), 0);
                if (need <= 0) continue;
                boolean open = myTier >= need, sel = st.id().equals(cur);
                p.sendMessage(Component.text((sel ? "> " : open ? "+ " : "- ") + st.id() + "  (" + st.name() + ")"
                                + (open ? "" : "  - unlocks at " + tierNameNo(need)),
                        sel ? NamedTextColor.GREEN : open ? NamedTextColor.WHITE : NamedTextColor.DARK_GRAY));
            }
            if (cur == null) p.sendMessage(Component.text("Currently off.", NamedTextColor.GRAY));
            return;
        }
        String want = a[1].toLowerCase(Locale.ROOT);
        if (want.equals("none") || want.equals("off")) {
            pdc.set(PARTICLE, PersistentDataType.STRING, "none");
            p.sendMessage(Component.text("Particles off.", NamedTextColor.GRAY));
            return;
        }
        if (want.equals("default")) {
            pdc.remove(PARTICLE);
            p.sendMessage(Component.text("Particle style reset to the default.", NamedTextColor.GRAY));
            return;
        }
        Style st = STYLES.stream().filter(x -> x.id().equals(want)).findFirst().orElse(null);
        int need = st == null ? 0 : styleTier.getOrDefault(st.id(), 0);
        if (st == null || need <= 0) {
            p.sendMessage(Component.text("Unknown style. Type /jolly particle to see the list.", NamedTextColor.RED));
            return;
        }
        if (myTier < need) {
            p.sendMessage(Component.text(st.name() + " unlocks at " + tierNameNo(need) + ".", NamedTextColor.RED));
            return;
        }
        pdc.set(PARTICLE, PersistentDataType.STRING, st.id());
        p.sendMessage(Component.text("Particle style: " + st.name() + ".", NamedTextColor.GREEN));
        if (!wearing(p)) p.sendMessage(Component.text("Wear your Santa Hat to see it.", NamedTextColor.GRAY));
    }

    private void jingleCommand(Player p, String[] a) {
        if (!jingleOn) { p.sendMessage(Component.text("Footstep jingle is disabled.", NamedTextColor.GRAY)); return; }
        if (tierIdx(get(p)) + 1 < jingleMinTier) {
            p.sendMessage(Component.text("Footstep jingle unlocks at " + tierNameNo(jingleMinTier) + ".", NamedTextColor.RED));
            return;
        }
        boolean on;
        if (a.length >= 2) {
            if (a[1].equalsIgnoreCase("on")) on = true;
            else if (a[1].equalsIgnoreCase("off")) on = false;
            else { p.sendMessage(Component.text("Usage: /jolly jingle [on|off]", NamedTextColor.RED)); return; }
        } else on = !jingleFor(p);
        p.getPersistentDataContainer().set(JINGLE, PersistentDataType.BYTE, (byte) (on ? 1 : 0));
        p.sendMessage(Component.text("Footstep jingle " + (on ? "on" : "off") + ".", NamedTextColor.GRAY));
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
        if (!walkEnabled || d > 2 || p.isInsideVehicle() || p.isGliding() || !canEarn(p)) return;

        String biome = p.getLocation().getBlock().getBiome().getKey().getKey();
        walkGain(p, d, snowBiomes.contains(biome) ? snowMult : 1.0);
    }

    /** Adds travelled distance; every walking-blocks-per-point blocks pays out, scaled by mult. */
    private void walkGain(Player p, double d, double mult) {
        UUID id = p.getUniqueId();
        double dist = walkDist.merge(id, d, Double::sum);
        if (dist < 1) return;
        double whole = Math.floor(dist);
        walkDist.put(id, dist - whole);
        double acc = walkPts.merge(id, whole / walkBlocks * walkPoints * mult, Double::sum);
        int pts = (int) acc;
        if (pts > 0) { walkPts.put(id, acc - pts); add(p, pts); }
    }

    /** Boating over ice pays out at iceMult x the walking rate. */
    @EventHandler(ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent e) {
        if (!iceBoatOn || !(e.getVehicle() instanceof Boat boat)) return;
        List<Entity> riders = boat.getPassengers();
        if (riders.isEmpty() || !(riders.get(0) instanceof Player p)) return;
        double dx = e.getTo().getX() - e.getFrom().getX(), dz = e.getTo().getZ() - e.getFrom().getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d < 0.01 || d > 6 || !canEarn(p)) return;
        Block at = e.getTo().getBlock();
        if (!iceBlocks.contains(at.getType()) && !iceBlocks.contains(at.getRelative(BlockFace.DOWN).getType())) return;
        walkGain(p, d, iceMult);
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
        if (wearing(p)) rollDuplicate(p, b, type); // duplication is a hat perk
    }

    /** Chance (per tier) to drop a second copy of what this ore just gave. Never for excluded blocks/items. */
    private void rollDuplicate(Player p, Block b, Material type) {
        if (p.getGameMode() == GameMode.CREATIVE || dupExclude.contains(type)) return;
        double chance = tiers.get(tierIdx(get(p))).dupChance();
        if (chance <= 0 || ThreadLocalRandom.current().nextDouble() >= chance) return;
        Location at = b.getLocation().add(0.5, 0.5, 0.5);
        boolean any = false;
        for (ItemStack d : b.getDrops(p.getInventory().getItemInMainHand(), p)) {
            if (d == null || d.getType().isAir() || dupExclude.contains(d.getType())) continue;
            b.getWorld().dropItemNaturally(at, d.clone());
            any = true;
        }
        if (!any) return;
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.4f);
        b.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, at, 6, 0.3, 0.3, 0.3, 0);
    }

    @EventHandler public void onChat(AsyncChatEvent e) {
        if (chatPrefixOn) {
            // Same layout as vanilla chat (<name> message), with the tier in front of the name.
            e.renderer((source, sourceDisplayName, message, viewer) -> {
                int t = tierCache.getOrDefault(source.getUniqueId(), 0);
                Component prefix = MM.deserialize(chatPrefixFormat,
                        Placeholder.unparsed("tier", tierName(t)),
                        Placeholder.unparsed("tiernumber", String.valueOf(t + 1)),
                        tierColor(t));
                return Component.translatable("chat.type.text", prefix.append(sourceDisplayName), message);
            });
        }
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
        if (e.getEntity() instanceof Player who) discGain(who, e.getItem().getItemStack());
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
        if (!(e.getEntity() instanceof Snowball sb)) return;
        candyBalls.remove(sb);
        if (!(sb.getShooter() instanceof Player p)) return;
        if (e.getHitEntity() instanceof Player victim) knockback(p, sb, victim);
        if (!snowballOn) return;
        if (!(e.getHitEntity() instanceof LivingEntity hit) || hit.equals(p)) return;
        if (cooling(p.getUniqueId(), "snowball", snowballCd)) return;
        add(p, snowballPts);
    }

    /** Candy-cane snowballs: tracked from launch, trail drawn in fastTick. */
    @EventHandler(ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        if (!candyOn || !(e.getEntity() instanceof Snowball sb) || !(sb.getShooter() instanceof Player p)) return;
        if (candyBalls.size() >= 256 || !wearing(p) || tierIdx(get(p)) + 1 < candyTier) return;
        candyBalls.put(sb, sb.getLocation());
    }

    /** Snowballs do no damage to players in vanilla; this pushes them back instead (tier + hat needed). */
    private void knockback(Player shooter, Snowball sb, Player victim) {
        if (!knockOn || victim.equals(shooter) || !wearing(shooter) || tierIdx(get(shooter)) + 1 < knockTier) return;
        GameMode gm = victim.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR || victim.isInvulnerable() || !victim.getWorld().getPVP()) return;
        Vector dir = sb.getVelocity().clone().setY(0);
        if (dir.lengthSquared() < 1.0E-4)
            dir = victim.getLocation().toVector().subtract(shooter.getLocation().toVector()).setY(0);
        if (dir.lengthSquared() < 1.0E-4) return;
        dir = dir.normalize().multiply(knockStrength).setY(knockLift);
        victim.setVelocity(victim.getVelocity().add(dir));
        victim.getWorld().spawnParticle(Particle.SNOWFLAKE, victim.getLocation().add(0, 1, 0), 10, 0.3, 0.4, 0.3, 0.05);
    }

    // ---- snowmen, bells, discs

    @EventHandler public void onInteract(PlayerInteractEvent e) {
        if (!snowmanOn || e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getItem() == null) return;
        Material m = e.getItem().getType();
        if (m == Material.CARVED_PUMPKIN || m == Material.JACK_O_LANTERN)
            pumpkinUse.put(e.getPlayer().getUniqueId(), Bukkit.getCurrentTick());
    }

    /** The golem spawns before BlockPlaceEvent, so credit whoever just right-clicked a pumpkin nearby. */
    @EventHandler(ignoreCancelled = true)
    public void onSnowmanBuilt(CreatureSpawnEvent e) {
        if (!snowmanOn || e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.BUILD_SNOWMAN) return;
        Location at = e.getLocation();
        int now = Bukkit.getCurrentTick();
        Player best = null;
        double bestD = 64; // within 8 blocks
        for (Map.Entry<UUID, Integer> en : pumpkinUse.entrySet()) {
            if (now - en.getValue() > 20) continue;
            Player q = Bukkit.getPlayer(en.getKey());
            if (q == null || q.getWorld() != at.getWorld()) continue;
            double d = q.getLocation().distanceSquared(at);
            if (d < bestD) { bestD = d; best = q; }
        }
        if (best == null || cooling(best.getUniqueId(), "snowman", snowmanCd)) return;
        add(best, snowmanPts);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBell(BellRingEvent e) {
        if (!bellOn || !(e.getEntity() instanceof Player p)) return;
        if (cooling(p.getUniqueId(), "bell", bellCd)) return;
        add(p, bellPts);
    }

    /** Taking a disc out of a chest (or any other container) counts the same as picking it up. */
    @EventHandler(ignoreCancelled = true)
    public void onInvClick(InventoryClickEvent e) {
        if (!discOn || !(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() == null) return;
        if (e.getClickedInventory().getType() == InventoryType.PLAYER) return; // only items coming from elsewhere
        ItemStack it = e.getCurrentItem();
        if (it == null || !discPoints.containsKey(it.getType())) return;
        switch (e.getAction()) {
            case PICKUP_ALL, PICKUP_HALF, PICKUP_ONE, PICKUP_SOME, MOVE_TO_OTHER_INVENTORY,
                 HOTBAR_SWAP -> discGain(p, it);
            default -> { }
        }
    }

    /** Pays out once per disc type per player, remembered on the player. */
    private void discGain(Player p, ItemStack it) {
        if (!discOn || it == null) return;
        Material m = it.getType();
        int pts = discPoints.getOrDefault(m, 0);
        if (pts <= 0 || !canEarn(p)) return;
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        String have = pdc.getOrDefault(DISCS, PersistentDataType.STRING, "");
        Set<String> got = new LinkedHashSet<>();
        for (String x : have.split(",")) if (!x.isEmpty()) got.add(x);
        if (!got.add(m.name())) return;
        pdc.set(DISCS, PersistentDataType.STRING, String.join(",", got));
        add(p, pts);
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

        PvpKill kill = pvpOn ? pvpKill(p) : null;
        if (kill == null) return;
        Player k = kill.killer();
        int lost = Math.min(pvpPenalty, get(k));
        if (lost > 0) set(k, get(k) - lost);
        e.deathMessage(MM.deserialize(pvpMessage,
                Placeholder.unparsed("killer", k.getName()),
                Placeholder.unparsed("victim", p.getName()),
                Placeholder.unparsed("method", kill.method()),
                Placeholder.component("weapon", kill.weapon()),
                Placeholder.unparsed("points", fmt(lost))));
    }

    /** Who killed this player and how, or null if it was not another player. */
    private PvpKill pvpKill(Player victim) {
        if (!(victim.getLastDamageCause() instanceof EntityDamageByEntityEvent ed)) return null;
        Entity d = ed.getDamager();
        if (d instanceof Player k1 && !k1.equals(victim))
            return new PvpKill(k1, pvpVerbs.get("melee"), weaponName(k1.getInventory().getItemInMainHand()));
        if (d instanceof Projectile pr && pr.getShooter() instanceof Player k2 && !k2.equals(victim)) {
            ItemStack hand = k2.getInventory().getItemInMainHand();
            Material hm = hand.getType();
            Component w = hm == Material.BOW || hm == Material.CROSSBOW || hm == Material.TRIDENT
                    ? weaponName(hand) : Component.translatable(pr.getType().translationKey());
            return new PvpKill(k2, pvpVerbs.get("projectile"), w);
        }
        if (d instanceof TNTPrimed tnt && tnt.getSource() instanceof Player k3 && !k3.equals(victim))
            return new PvpKill(k3, pvpVerbs.get("explosion"), Component.translatable(Material.TNT.translationKey()));
        return null;
    }

    private Component weaponName(ItemStack it) {
        if (it == null || it.getType().isAir()) return Component.text("their bare fists");
        if (it.hasItemMeta() && it.getItemMeta().hasDisplayName()) return it.getItemMeta().displayName();
        return Component.translatable(it.getType().translationKey());
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
        refreshAllHats(p);
        updateTab(p);
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        tierCache.remove(id);
        walkDist.remove(id); walkPts.remove(id); lastMove.remove(id);
        lastGainSound.remove(id); playSecs.remove(id); jingleTick.remove(id); passiveSecs.remove(id); pumpkinUse.remove(id);
        String prefix = id.toString();
        cooldowns.keySet().removeIf(k -> k.startsWith(prefix));
    }

    // ------------------------------------------------------------------ /jolly

    @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 0) {
            if (!(s instanceof Player p)) { s.sendMessage("Players only. Try /jolly get <player>."); return true; }
            int j = get(p), t = tierIdx(j);
            String next = t + 1 < tiers.size() ? "next: " + tierName(t + 1) + " at " + fmt(tiers.get(t + 1).threshold()) : "max tier";
            p.sendMessage(Component.text("Jolliness: " + fmt(j) + " (" + tierName(t) + ", " + next + ")", NamedTextColor.GOLD));
            if (requireHat && !wearing(p)) p.sendMessage(Component.text("Wear your Santa Hat to earn more.", NamedTextColor.GRAY));
            return true;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("particle") || sub.equals("particles") || sub.equals("jingle") || sub.equals("cheer")) {
            if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
            switch (sub) {
                case "jingle" -> jingleCommand(p, a);
                case "cheer" -> cheer(p);
                default -> particleCommand(p, a);
            }
            return true;
        }
        if (!s.hasPermission("jolly.admin")) { s.sendMessage("No permission."); return true; }

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
        boolean admin = s.hasPermission("jolly.admin");
        if (a.length == 1) {
            List<String> subs = new ArrayList<>(List.of("particle", "jingle", "cheer"));
            if (admin) subs.addAll(List.of("add", "set", "get", "give", "reload"));
            return subs.stream().filter(x -> x.startsWith(a[0].toLowerCase(Locale.ROOT))).toList();
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (a.length == 2 && (sub.equals("particle") || sub.equals("particles")) && s instanceof Player p) {
            List<String> opts = new ArrayList<>(List.of("none", "default"));
            for (Style st : STYLES) if (unlocked(st.id(), p)) opts.add(st.id());
            return opts.stream().filter(x -> x.startsWith(a[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (a.length == 2 && sub.equals("jingle")) {
            return List.of("on", "off").stream().filter(x -> x.startsWith(a[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (!admin) return List.of();
        if (a.length == 2 && List.of("add", "set", "get", "give").contains(sub)) return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName).filter(n -> n.toLowerCase(Locale.ROOT).startsWith(a[1].toLowerCase(Locale.ROOT))).toList();
        if (a.length == 3 && (sub.equals("add") || sub.equals("set"))) return List.of("10", "100", "1000");
        return List.of();
    }
}
