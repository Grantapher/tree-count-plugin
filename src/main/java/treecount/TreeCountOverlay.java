package treecount;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

@Slf4j
public class TreeCountOverlay extends Overlay
{
	private final TreeCountPlugin plugin;
	private final TreeCountConfig config;
	private final Client client;

	@Inject
	private TreeCountOverlay(TreeCountPlugin plugin, TreeCountConfig config, Client client)
	{
		this.plugin = plugin;
		this.config = config;
		this.client = client;
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPosition(OverlayPosition.DYNAMIC);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (plugin.isPlayerInWoodcuttingGuild(client.getLocalPlayer()))
		{
			return null;
		}

		renderDebugOverlay(graphics);

		plugin.getTreePlayerCountMap().forEach((gameObject, choppers) ->
		{
			if (choppers == null || choppers <= 0 || Tree.findForestryTree(gameObject.getId()) == null)
			{
				return;
			}

			final String text = String.valueOf(choppers);
			Optional.ofNullable(Perspective.getCanvasTextLocation(client, graphics, gameObject.getLocalLocation(), text, 0))
				.ifPresent(point -> OverlayUtil.renderTextLocation(graphics, point, text, getColorForChoppers(choppers)));
		});

		return null;
	}

	private static Color getColorForChoppers(int choppers)
	{
		if (choppers >= 10)
		{
			return Color.GREEN;
		}
		else if (choppers >= 7)
		{
			return Color.YELLOW;
		}
		else if (choppers >= 4)
		{
			return Color.ORANGE;
		}
		else
		{
			return Color.RED;
		}
	}

	private static final Random random = ThreadLocalRandom.current();

	private static final Map<GameObject, Color> colorMap = new WeakHashMap<>();

	private void renderDebugOverlay(Graphics2D graphics)
	{
		if (config.renderFacingTree())
		{
			renderFacingTree(graphics);
		}

		if (config.renderTreeTiles())
		{
			renderTreeTiles(graphics);
		}

	}

	private void renderFacingTree(Graphics2D graphics)
	{
		Optional.ofNullable(client.getLocalPlayer())
			.flatMap(plugin::getFacingTree)
			.ifPresent(tree -> OverlayUtil.renderTileOverlay(graphics, tree, "", Color.GREEN));
	}

	private void renderTreeTiles(Graphics2D graphics)
	{
		plugin.getTreeTileMap().forEach((tree, tiles) ->
			{
				final Color color = colorMap.computeIfAbsent(tree, (unused) -> Color.getHSBColor(random.nextFloat(), 1f, 1f));
				tiles.forEach(worldPoint ->
					{
						Optional.ofNullable(LocalPoint.fromWorld(client, worldPoint))
							.map(localPoint -> Perspective.getCanvasTilePoly(client, localPoint))
							.ifPresent(poly -> OverlayUtil.renderPolygon(graphics, poly, color));
					}
				);
			}
		);
	}
}
