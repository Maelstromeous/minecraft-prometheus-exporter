package com.github.cpburnz.minecraft_prometheus_exporter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.mojang.authlib.GameProfile;

import io.prometheus.client.Collector;
import io.prometheus.client.GaugeMetricFamily;
import io.prometheus.client.Histogram;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldType;
import net.minecraftforge.common.DimensionManager;

/**
 * This class collects stats from the Minecraft server for export.
 */
public class MinecraftCollector extends Collector implements Collector.Describable {

	/**
	 * The histogram buckets to use for ticks.
	 */
	private static final double[] TICK_BUCKETS = new double[] {
		0.01,
		0.025,
		0.05,
		0.10,
		0.25,
		0.5,
		1.0,
	};

	/**
	 * The Minecraft server.
	 */
	@SuppressWarnings("FieldMayBeFinal")
	private MinecraftServer mc_server;

	/**
	 * Histogram metrics for server tick timing.
	 */
	@SuppressWarnings("FieldMayBeFinal")
	private Histogram server_tick_seconds;

	/**
	 * Timer used for server ticks.
	 */
	private Histogram.Timer server_tick_timer;

	/**
	 * Histogram metrics for world tick timing.
	 */
	@SuppressWarnings("FieldMayBeFinal")
	private Histogram world_tick_seconds;

	/**
	 * Timer used for world ticks.
	 */
	private Histogram.Timer world_tick_timer;

	/**
	 * Constructs the instance.
	 *
	 * @param mc_server The Minecraft server.
	 */
	public MinecraftCollector(MinecraftServer mc_server) {
		this.mc_server = mc_server;

		// Setup server metrics.
		this.server_tick_seconds = Histogram.build()
			.buckets(TICK_BUCKETS)
			.name("mc_server_tick_seconds")
			.help("Stats on server tick times.")
			.create();

		this.world_tick_seconds = Histogram.build()
			.buckets(TICK_BUCKETS)
			.name("mc_world_tick_seconds")
			.labelNames("id", "name")
			.help("Stats on world tick times.")
			.create();
	}

	/**
	 * Return all metrics for the collector.
	 *
	 * @return The collector metrics.
	 */
	@Override
	public List<MetricFamilySamples> collect() {
		// Collect metrics.
		MetricFamilySamples player_list = this.collectPlayerList();
		List<MetricFamilySamples> server_tick = this.server_tick_seconds.collect();
		MetricFamilySamples world_chunks_loaded = this.collectWorldChunksLoaded();
		List<MetricFamilySamples> world_tick = this.world_tick_seconds.collect();

		// Aggregate metrics.
		ArrayList<MetricFamilySamples> metrics = new ArrayList<>(
			1 /* player_list */
			+ server_tick.size()
			+ 1 /* world_chunks_loaded */
			+ world_tick.size()
		);
		metrics.add(player_list);
		metrics.addAll(server_tick);
		metrics.add(world_chunks_loaded);
		metrics.addAll(world_tick);

		return metrics;
	}

	/**
	 * Get the active players.
	 *
	 * @return The player list metric.
	 */
	private GaugeMetricFamily collectPlayerList() {
		GaugeMetricFamily metric = newPlayerListMetric();
		for (EntityPlayerMP player : this.mc_server.getPlayerList().getPlayers()) {
			GameProfile profile = player.getGameProfile();
			String id = profile.getId().toString();
			String name = profile.getName();
			metric.addMetric(Arrays.asList(id, name), 1);
		}
		return metric;
	}

	/**
	 * Get the number of loaded world chunks.
	 *
	 * @return The world chunks loaded metric.
	 */
	private GaugeMetricFamily collectWorldChunksLoaded() {
		GaugeMetricFamily metric = newWorldChunksLoadedMetric();
		for (WorldServer world : DimensionManager.getWorlds()) {
			WorldType type = world.getWorldType();
			String id = Integer.toString(type.getId());
			String name = type.getName();
			int loaded = world.getChunkProvider().getLoadedChunkCount();
			metric.addMetric(Arrays.asList(id, name), loaded);
		}
		return metric;
	}

	/**
	 * Return all metric descriptions for the collector.
	 *
	 * @return The collector metric descriptions.
	 */
	@Override
	public List<MetricFamilySamples> describe() {
		// Aggregate metric descriptions.
		ArrayList<MetricFamilySamples> descs = new ArrayList<>();
		descs.add(newPlayerListMetric());
		descs.addAll(this.server_tick_seconds.describe());
		descs.add(newWorldChunksLoadedMetric());
		descs.addAll(this.world_tick_seconds.describe());
		return descs;
	}

	/**
	 * Create a new metric for the player list.
	 *
	 * @return The player list metric.
	 */
	private static GaugeMetricFamily newPlayerListMetric() {
		return new GaugeMetricFamily(
			"mc_player_list",
			"The players connected to the server.",
			Arrays.asList("id", "name")
		);
	}

	/**
	 * Create a new metric for the world chunks loaded.
	 *
	 * @return The world chunks loaded metric.
	 */
	private static GaugeMetricFamily newWorldChunksLoadedMetric() {
		return new GaugeMetricFamily(
			"mc_world_chunks_loaded",
			"The number of loaded world chunks.",
			Arrays.asList("id", "name")
		);
	}

	/**
	 * Record when a server tick begins.
	 */
	public void startServerTick() {
		if (this.server_tick_timer != null) {
			throw new IllegalStateException("Server tick started before stopping previous tick.");
		}
		this.server_tick_timer = this.server_tick_seconds.startTimer();
	}

	/**
	 * Recorc when a world tick begins.
	 *
	 * @param world The world.
	 */
	public void startWorldTick(World world) {
		if (this.world_tick_timer != null) {
			throw new IllegalStateException("World tick started before stopping previous tick.");
		}
		WorldType type = world.getWorldType();
		String id = Integer.toString(type.getId());
		String name = type.getName();
		this.world_tick_timer = this.world_tick_seconds.labels(id, name).startTimer();
	}

	/**
	 * Record when a server tick finishes.
	 */
	public void stopServerTick() {
		if (this.server_tick_timer == null) {
			throw new IllegalStateException("Server tick stopped without an active tick.");
		}
		this.server_tick_timer.observeDuration();
		this.server_tick_timer = null;
	}

	/**
	 * Record when a world tick finishes.
	 *
	 * @param world The world.
	 */
	public void stopWorldTick(@SuppressWarnings("unused") World world) {
		if (this.world_tick_timer == null) {
			throw new IllegalStateException("World tick stopped without an active tick.");
		}
		this.world_tick_timer.observeDuration();
		this.world_tick_timer = null;
	}
}
