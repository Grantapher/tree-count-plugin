package treecount;

import com.google.common.collect.ImmutableSet;
import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.AnimationID;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.coords.Angle;
import net.runelite.api.coords.Direction;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.PlayerDespawned;
import net.runelite.api.events.PlayerSpawned;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
	name = "Tree Count",
	description = "Show the number of players chopping a tree",
	tags = {"woodcutting", "wc", "tree", "count", "forestry", "overlay"}
)
public class TreeCountPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private TreeCountConfig config;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private TreeCountOverlay overlay;

	@Getter
	private final Map<GameObject, Integer> treePlayerCountMap = new HashMap<>();
	private final Map<Player, GameObject> playerTreeMap = new HashMap<>();
	@Getter
	private final Map<GameObject, List<WorldPoint>> treeTileMap = new HashMap<>();
	private final Map<WorldPoint, GameObject> tileTreeMap = new HashMap<>();
	// This map is used to track player orientation changes for only players that are chopping trees
	private final Map<Player, Integer> playerOrientationMap = new ConcurrentHashMap<>();

	private int previousPlane;

	@Provides
	TreeCountConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(TreeCountConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		treePlayerCountMap.clear();
		treeTileMap.clear();
		tileTreeMap.clear();
		playerTreeMap.clear();
		playerOrientationMap.clear();
		previousPlane = -1;
	}

	@Subscribe
	public void onGameTick(GameTick gameTick)
	{
		if (isPlayerInWoodcuttingGuild(client.getLocalPlayer()))
		{
			return;
		}

		// Event runs third (or last) upon login
		int currentPlane = client.getPlane();
		if (previousPlane != currentPlane)
		{
			// Only clear values because sometimes the trees are still there when changing planes (Top of Seer's Bank)
			treePlayerCountMap.replaceAll((k, v) -> 0);
			previousPlane = currentPlane;
		}

		playerOrientationMap.forEach((player, previousOrientation) -> {
			int currentOrientation = player.getOrientation();
			if (currentOrientation != previousOrientation)
			{
				onPlayerOrientationChanged(new PlayerOrientationChanged(player, previousOrientation, currentOrientation));
			}
		});
	}

	@Subscribe
	public void onGameObjectSpawned(final GameObjectSpawned event)
	{
		// Event runs first upon login
		GameObject gameObject = event.getGameObject();

		if (isGameObjectInWoodcuttingGuild(gameObject))
		{
			return;
		}

		Tree.findTree(gameObject.getId()).ifPresent(tree ->
			{
				log.debug("Tree {} spawned at {}", tree, gameObject.getLocalLocation());
				treePlayerCountMap.put(gameObject, 0);
				List<WorldPoint> points = getPoints(gameObject);
				treeTileMap.put(gameObject, points);
				points.forEach(point -> tileTreeMap.put(point, gameObject));
			}
		);
	}

	private List<WorldPoint> getPoints(GameObject gameObject)
	{
		WorldPoint minPoint = getSWWorldPoint(gameObject);
		WorldPoint maxPoint = getNEWorldPoint(gameObject);

		if (minPoint.equals(maxPoint))
		{
			return Collections.singletonList(minPoint);
		}

		final int plane = minPoint.getPlane();
		final List<WorldPoint> list = new ArrayList<>();
		for (int x = minPoint.getX(); x <= maxPoint.getX(); x++)
		{
			for (int y = minPoint.getY(); y <= maxPoint.getY(); y++)
			{
				list.add(new WorldPoint(x, y, plane));
			}
		}
		return list;
	}

	@Subscribe
	public void onGameObjectDespawned(final GameObjectDespawned event)
	{
		final GameObject gameObject = event.getGameObject();
		if (isGameObjectInWoodcuttingGuild(gameObject))
		{
			return;
		}

		Tree.findTree(gameObject.getId()).ifPresent(tree ->
			{
				treePlayerCountMap.remove(gameObject);
				List<WorldPoint> points = treeTileMap.remove(gameObject);
				if (points != null)
				{
					points.forEach(tileTreeMap::remove);
				}
			}
		);
	}

	@Subscribe
	public void onGameStateChanged(final GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOADING)
		{
			treePlayerCountMap.clear();
			treeTileMap.clear();
			tileTreeMap.clear();
			playerTreeMap.clear();
			playerOrientationMap.clear();
		}
	}

	@Subscribe
	public void onPlayerSpawned(final PlayerSpawned event)
	{
		// Event runs second upon login
		Player player = event.getPlayer();
		log.debug("Player {} spawned at {}", player.getName(), player.getWorldLocation());
		onPlayerOrientationChanged(new PlayerOrientationChanged(player, -1, player.getOrientation()));
	}

	@Subscribe
	public void onPlayerDespawned(final PlayerDespawned event)
	{
		Player player = event.getPlayer();

		if (player.equals(client.getLocalPlayer()))
		{
			return;
		}

		if (isPlayerInWoodcuttingGuild(player))
		{
			return;
		}

		removeFromTreeMaps(player);
		playerOrientationMap.remove(player);
	}

	@Subscribe
	public void onAnimationChanged(final AnimationChanged event)
	{
		if (!(event.getActor() instanceof Player))
		{
			return;
		}

		Player player = (Player) event.getActor();

		if (Objects.equals(player, client.getLocalPlayer()))
		{
			return;
		}

		// Check combat level to avoid NPE. Not sure why this happens, maybe the Player isn't really a player?
		// The player isn't null, but all the fields are
		if (player.getCombatLevel() != 0 && isPlayerInWoodcuttingGuild(player))
		{
			return;
		}

		if (isWoodcutting(player))
		{
			addToTreeFocusedMaps(player);
		}
		else if (player.getAnimation() == AnimationID.IDLE)
		{
			removeFromTreeMaps(player);
		}
	}

	@Subscribe
	public void onPlayerOrientationChanged(final PlayerOrientationChanged event)
	{
		Player player = event.getPlayer();

		log.debug("Player {} orientation changed from {} to {}", player.getName(), event.getPreviousOrientation(), event.getCurrentOrientation());

		if (player.equals(client.getLocalPlayer()))
		{
			return;
		}

		if (isPlayerInWoodcuttingGuild(player))
		{
			return;
		}

		playerOrientationMap.put(player, event.getCurrentOrientation());
		if (isWoodcutting(player))
		{
			addToTreeFocusedMaps(player);
		}
	}

	private static final Set<Integer> WOODCUTTING_ANIMATION_ID_SET = ImmutableSet.of(
		AnimationID.WOODCUTTING_BRONZE,
		AnimationID.WOODCUTTING_IRON,
		AnimationID.WOODCUTTING_STEEL,
		AnimationID.WOODCUTTING_BLACK,
		AnimationID.WOODCUTTING_MITHRIL,
		AnimationID.WOODCUTTING_ADAMANT,
		AnimationID.WOODCUTTING_RUNE,
		AnimationID.WOODCUTTING_GILDED,
		AnimationID.WOODCUTTING_DRAGON,
		AnimationID.WOODCUTTING_DRAGON_OR,
		AnimationID.WOODCUTTING_INFERNAL,
		AnimationID.WOODCUTTING_3A_AXE,
		AnimationID.WOODCUTTING_CRYSTAL,
		AnimationID.WOODCUTTING_TRAILBLAZER
	);

	private boolean isWoodcutting(Actor actor)
	{
		return WOODCUTTING_ANIMATION_ID_SET.contains(actor.getAnimation());
	}

	void addToTreeFocusedMaps(Player player)
	{
		getFacingTree(player)
			.ifPresent(facingTree ->
				{
					GameObject previousTreeInMap = playerTreeMap.put(player, facingTree);
					if (previousTreeInMap != null)
					{
						treePlayerCountMap.computeIfPresent(previousTreeInMap, (unused, value) -> Math.max(0, value - 1));
					}
					treePlayerCountMap.merge(facingTree, 1, Integer::sum);
				}
			);
	}

	void removeFromTreeMaps(Player player)
	{
		GameObject tree = playerTreeMap.remove(player);
		if (tree != null)
		{
			treePlayerCountMap.computeIfPresent(tree, (unused, value) -> Math.max(0, value - 1));
		}
	}

	Optional<GameObject> getFacingTree(Actor actor)
	{
		if (tileTreeMap.isEmpty())
		{
			return Optional.empty();
		}

		WorldPoint actorLocation = actor.getWorldLocation();
		Direction direction = new Angle(actor.getOrientation()).getNearestDirection();
		WorldPoint facingPoint = neighborPoint(actorLocation, direction);
		if (actor != client.getLocalPlayer())
		{
			log.debug("Actor: {}, Direction: {}", actor.getName(), direction);
		}
		return Optional.ofNullable(tileTreeMap.get(facingPoint));
	}

	private WorldPoint neighborPoint(WorldPoint point, Direction direction)
	{
		switch (direction)
		{
			case NORTH:
				return point.dy(1);
			case SOUTH:
				return point.dy(-1);
			case EAST:
				return point.dx(1);
			case WEST:
				return point.dx(-1);
			default:
				throw new IllegalStateException();
		}
	}

	private WorldPoint getSWWorldPoint(GameObject gameObject)
	{
		return getWorldPoint(gameObject, GameObject::getSceneMinLocation);
	}

	private WorldPoint getNEWorldPoint(GameObject gameObject)
	{
		return getWorldPoint(gameObject, GameObject::getSceneMaxLocation);
	}

	private WorldPoint getWorldPoint(GameObject gameObject, Function<GameObject, Point> pointFunction)
	{
		Point point = pointFunction.apply(gameObject);
		return WorldPoint.fromScene(client, point.getX(), point.getY(), gameObject.getPlane());
	}

	boolean isPlayerInWoodcuttingGuild(Player player)
	{
		return isRegionInWoodcuttingGuild(player.getWorldLocation().getRegionID());
	}

	boolean isGameObjectInWoodcuttingGuild(GameObject gameObject)
	{
		return isRegionInWoodcuttingGuild(gameObject.getWorldLocation().getRegionID());
	}

	boolean isRegionInWoodcuttingGuild(int regionID)
	{
		return regionID == 6198 || regionID == 6454;
	}
}
