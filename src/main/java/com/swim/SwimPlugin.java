package com.swim;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import javax.inject.Inject;
import java.time.Duration;
import java.util.*;
import java.time.Instant;
import static net.runelite.api.ItemID.MERMAIDS_TEAR;
import static net.runelite.api.Skill.THIEVING;

@PluginDescriptor(
        name = "Underwater Agility",
        description = "Highlight active chest, show timer, highlight obstacles ",
        tags = {"agility", "thieving", "skilling", "overlay"}
)
@Slf4j
@Getter
public class SwimPlugin extends Plugin
{
    private static final Set<Integer> SWIM_REGIONS = ImmutableSet.of(
            15008,
            15264
    );

    private static final Set<Integer> HAZARD_OBJECT_IDS = ImmutableSet.of(
            30779,
            30780,
            30781,
            30782,
            30783
    );

    private static final Set<Integer> CHEST_IDS = ImmutableSet.of(
            30969,
            30971
    );

    private static final List<String> DIRECTION_LABELS = ImmutableList.of(
            "E",
            "ENE",
            "NE",
            "NNE",
            "N",
            "NNW",
            "NW",
            "WNW",
            "W",
            "WSW",
            "SW",
            "SSW",
            "S",
            "SSE",
            "SE",
            "ESE"
    );

    private static final int PUFFERFISH_ID = 8667;

    private WorldPoint lastChestPosition;

    private SwimTimer timer;

    private int lastXpDrop;

    @Getter
    private Instant start;

    @Getter
    private Instant lastXpTime;

    @Getter
    private int tearCount;

    @Getter
    private boolean chestLoaded;

    @Getter
    private int tearsPerHour;

    @Getter
    private int distance;

    @Getter
    private String direction;

    @Getter
    private boolean underwater;

    @Getter
    private final Map<TileObject,Tile> hazards = new HashMap<>();

    @Getter
    private final Set<NPC> puffers = new HashSet<>();

    @Inject
    private Client client;

    @Inject
    private InfoBoxManager infoBoxManager;

    @Inject
    private ItemManager itemManager;

    @Inject
    private ClientThread clientThread;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private SwimOverlay overlay;

    @Inject
    private SwimConfig config;

    @Inject
    private SwimMinimapOverlay minimapOverlay;

    @Inject
    private TearCountOverlay tearOverlay;

    @Provides
    SwimConfig provideConfig(ConfigManager configManager) { return configManager.getConfig(SwimConfig.class); }

    @Override
    protected void startUp() throws Exception
    {
        overlayManager.add(overlay);
        overlayManager.add(tearOverlay);
        overlayManager.add(minimapOverlay);
    }

    @Override
    protected void shutDown()
    {
        overlayManager.remove(overlay);
        overlayManager.remove(tearOverlay);
        overlayManager.remove(minimapOverlay);
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        switch (event.getGameState())
        {
            case HOPPING:
            case LOGIN_SCREEN:
                lastChestPosition = null;
                puffers.clear();
                tearCount = 0;
                tearsPerHour = -1;
                distance = -1;
                direction = null;
                underwater = false;
                start = null;
                lastXpTime = null;
                timer = null;
                chestLoaded = false;
                break;
            case LOADING:
                lastXpDrop = client.getSkillExperience(THIEVING);
                hazards.clear();
                break;
            case LOGGED_IN:
                if (underwater)
                {
                    lastChestPosition = null;
                }
                break;
        }
    }

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        checkUnderwater();

        if (underwater && client.hasHintArrow())
        {
            WorldPoint newChestPosition = client.getHintArrowPoint();
            WorldPoint oldChestPosition = lastChestPosition;

            lastChestPosition = newChestPosition;
            checkChestLoaded();

            if (newChestPosition != null && client.getLocalPlayer() != null)
            {
                updateDirection(newChestPosition);

                distance = client.getLocalPlayer().getWorldLocation().distanceTo(newChestPosition);
            }

            if (oldChestPosition != null && newChestPosition != null &&
                    (oldChestPosition.getX() != newChestPosition.getX() ||
                            oldChestPosition.getY() != newChestPosition.getY()))
            {
                if (config.isSoundEnabled() && tearCount > 0)
                {
                    client.playSoundEffect(SoundEffectID.GE_COIN_TINKLE);
                }

                if (config.isTimerShown())
                {
                    showNewSwimTimer();
                }
            } else if (config.isTimerShown())
            {
                if (timer != null && Duration.between(Instant.now(),timer.getEndTime()).isNegative())
                {
                    showNewSwimTimer();
                }
            }
        } else if (timer != null)
        {
            removeSwimTimer();
        }

        if (start != null)
        {
            Duration sessionDuration = Duration.between(start, Instant.now());

            if (tearCount > 1)
            {
                tearsPerHour = (int) ((tearCount - 1) * 36e5 / sessionDuration.toMillis());
            }

            Duration idleDuration = Duration.between(lastXpTime, Instant.now());

            if (config.timeout() > 0 && idleDuration.toMinutes() >= config.timeout())
            {
                tearCount = 0;
                tearsPerHour = -1;
                start = null;
                lastXpTime = null;
            }
        }
    }

    public void checkUnderwater()
    {
        underwater = false;

        Player local = client.getLocalPlayer();

        if (local != null)
        {
            WorldPoint location = local.getWorldLocation();
            underwater = SWIM_REGIONS.contains(location.getRegionID());
        }
    }

    private void removeSwimTimer()
    {
        timer = null;
        infoBoxManager.removeIf(infoBox -> infoBox instanceof SwimTimer);
    }

    private void showNewSwimTimer()
    {
        removeSwimTimer();
        timer = new SwimTimer(this, itemManager.getImage(MERMAIDS_TEAR));
        infoBoxManager.addInfoBox(timer);
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (!config.isTimerShown())
        {
            removeSwimTimer();
        }
    }

    public void updateDirection(WorldPoint chestPosition)
    {
        if (client.getLocalPlayer() == null)
        {
            return;
        }
        WorldPoint playerPosition = client.getLocalPlayer().getWorldLocation();
        int dx = chestPosition.getX()-playerPosition.getX();
        int dy = chestPosition.getY()-playerPosition.getY();
        double theta = Math.atan2(dy,dx);

        if (theta < 0)
        {
            theta = 2*Math.PI+theta;
        }

        int bin;

        switch (config.compassDirections())
        {
            case _4:
                bin = 4;
                break;
            case _8:
                bin = 8;
                break;
            case _16:
                bin = 16;
                break;
            default:
                return;
        }

        double angle = Math.PI/bin;

        for (int i = 0; angle < 2*Math.PI; i += 16/bin)
        {
            if (theta < angle)
            {
                direction = DIRECTION_LABELS.get(i);
                return;
            }

            angle += 2*Math.PI/bin;
        }

        direction = DIRECTION_LABELS.get(0);
    }


    @Subscribe
    public void onNpcSpawned(NpcSpawned event)
    {
        NPC npc = event.getNpc();

        if (npc.getId() == PUFFERFISH_ID)
        {
            puffers.add(npc);
        }
    }

    @Subscribe
    public void onNpcChanged(NpcChanged event)
    {
        NPC npc = event.getNpc();
        puffers.remove(npc);

        if (npc.getId() == PUFFERFISH_ID)
        {
            puffers.add(npc);
        }
    }

    @Subscribe
    public void onNpcDespawned(NpcDespawned event)
    {
        NPC npc = event.getNpc();
        puffers.remove(npc);
    }

    @Subscribe
    public void onGameObjectSpawned(GameObjectSpawned event)
    {
        onTileObject(event.getTile(), null, event.getGameObject());
    }

    @Subscribe
    public void onGameObjectChanged(GameObjectChanged event)
    {
        onTileObject(event.getTile(), event.getPrevious(), event.getGameObject());
    }

    @Subscribe
    public void onGameObjectDespawned(GameObjectDespawned event)
    {
        onTileObject(event.getTile(), event.getGameObject(), null);
    }

    public void onTileObject(Tile tile, TileObject oldObject, TileObject newObject)
    {
        hazards.remove(oldObject);

        if (newObject == null)
        {
            return;
        }

        if (HAZARD_OBJECT_IDS.contains(newObject.getId()))
        {
            hazards.put(newObject, tile);
        }

        checkChestLoaded();
    }



    @Subscribe
    public void onStatChanged(StatChanged statChanged)
    {
        if (statChanged.getSkill() != THIEVING)
        {
            return;
        }

        int xpChange = statChanged.getXp()-lastXpDrop;
        lastXpDrop = statChanged.getXp();

        if (xpChange > 5 || !underwater)
        {
            return;
        }

        lastXpTime = Instant.now();
        tearCount++;

        if (start == null)
        {
            start = Instant.now();
        }
    }

    public void checkChestLoaded()
    {
        chestLoaded = false;

        if (lastChestPosition == null)
        {
            return;
        }

        Tile[][][] tiles = client.getScene().getTiles();
        int maxX = tiles[0].length-1;
        int maxY = tiles[0][0].length-1;

        if (tiles[0][0][0] == null)
        {
            return;
        }

        WorldPoint nearCorner = tiles[0][0][0].getWorldLocation();
        WorldPoint farCorner = tiles[0][maxX][maxY].getWorldLocation();

        if (lastChestPosition.getX() < nearCorner.getX() || lastChestPosition.getY() < nearCorner.getY() ||
            lastChestPosition.getX() > farCorner.getX() || lastChestPosition.getY() > farCorner.getY())
        {
            return;
        }

        chestLoaded = true;
    }
}
