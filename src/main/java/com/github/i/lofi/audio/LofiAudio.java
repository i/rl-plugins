package com.github.i.lofi.audio;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.sound.sampled.SourceDataLine;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import com.github.i.lofi.LofiConfig;

/**
 * Gives the game's audio a lo-fi tape feel by swapping its output lines for {@link LofiLine}s.
 * <p>
 * The game's audio players each write to a {@code SourceDataLine} field. Client class and field names are obfuscated
 * and change with game updates, so they're found by type instead: the player class is whichever declares a
 * {@code SourceDataLine} field, and players are reached through static fields that hold one, an array of them, or an
 * object that does. Lines are checked periodically, since the game reopens them when audio is toggled.
 * <p>
 * This uses reflection on client internals, so it only works in local builds, not on the Plugin Hub.
 */
@Slf4j
@Singleton
public class LofiAudio {
	private static final long POLL_MILLIS = 500;

	@Inject
	private Client client;

	@Inject
	private LofiConfig config;

	/** A way to reach audio players, re-read every poll since the game may replace them. */
	private interface PlayerSource {
		void collect(Set<Object> players) throws IllegalAccessException;
	}

	private ScheduledExecutorService executor;
	private final List<PlayerSource> playerSources = new ArrayList<>();
	private Field lineField;
	private boolean discoveryFailed;
	private final Map<Object, LofiLine> wrapped = new IdentityHashMap<>();

	public synchronized void startUp() {
		if (executor != null)
			return;
		executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "Lo-Fi audio setup");
			thread.setDaemon(true);
			return thread;
		});
		executor.scheduleWithFixedDelay(this::poll, 0, POLL_MILLIS, TimeUnit.MILLISECONDS);
	}

	public synchronized void shutDown() {
		if (executor == null)
			return;
		executor.shutdownNow();
		try {
			executor.awaitTermination(2, TimeUnit.SECONDS);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		executor = null;
		restoreAll();
	}

	private synchronized void poll() {
		try {
			if (lineField == null && !discoveryFailed)
				discover();
			if (lineField == null)
				return;

			if (!config.lofiAudio()) {
				restoreAll();
				return;
			}

			double speed = config.lofiSpeed() / 100.0;
			double depth = config.lofiWobble() / 100.0;
			float lowCut = config.lofiLowCut();
			float highCut = config.lofiHighCut();
			float saturation = config.lofiSaturation() / 100f;
			Set<Object> players = Collections.newSetFromMap(new IdentityHashMap<>());
			for (PlayerSource source : playerSources)
				source.collect(players);

			for (Object player : players) {
				Object line = lineField.get(player);
				if (line instanceof LofiLine) {
					((LofiLine) line).setSpeed(speed, depth);
					((LofiLine) line).setTone(lowCut, highCut, saturation);
					continue;
				}
				if (!(line instanceof SourceDataLine) || !((SourceDataLine) line).isOpen())
					continue;

				// A new or reopened line: any wrapper we had for this player is stale
				LofiLine stale = wrapped.remove(player);
				if (stale != null)
					stale.detach();

				LofiLine lofi = new LofiLine((SourceDataLine) line);
				lofi.setSpeed(speed, depth);
				lofi.setTone(lowCut, highCut, saturation);
				lineField.set(player, lofi);
				wrapped.put(player, lofi);
				log.debug("Lo-fi audio attached to {} ({})", player.getClass().getName(), lofi.getFormat());
			}
		} catch (Throwable ex) {
			log.warn("Lo-fi audio failed, leaving game audio untouched", ex);
			discoveryFailed = true;
			lineField = null;
			restoreAll();
		}
	}

	private void restoreAll() {
		for (Map.Entry<Object, LofiLine> entry : wrapped.entrySet()) {
			try {
				if (lineField != null && lineField.get(entry.getKey()) == entry.getValue())
					lineField.set(entry.getKey(), entry.getValue().getReal());
			} catch (IllegalAccessException ex) {
				log.warn("Unable to restore a game audio line", ex);
			}
			entry.getValue().detach();
		}
		wrapped.clear();
	}

	/**
	 * Finds the audio player class and the static fields leading to its instances.
	 */
	private void discover() throws Exception {
		ClassLoader loader = client.getClass().getClassLoader();
		List<Class<?>> classes = loadClientClasses(loader);

		Class<?> playerClass = null;
		for (Class<?> clazz : classes) {
			for (Field field : declaredFields(clazz)) {
				if (!Modifier.isStatic(field.getModifiers()) && field.getType() == SourceDataLine.class) {
					playerClass = clazz;
					lineField = field;
					break;
				}
			}
			if (playerClass != null)
				break;
		}

		if (playerClass == null) {
			log.warn("Lo-fi audio couldn't find the game's audio player, leaving game audio untouched");
			discoveryFailed = true;
			return;
		}
		lineField.setAccessible(true);

		for (Class<?> clazz : classes) {
			for (Field field : declaredFields(clazz)) {
				if (!Modifier.isStatic(field.getModifiers()))
					continue;
				if (holdsPlayers(field.getType(), playerClass)) {
					field.setAccessible(true);
					playerSources.add(players -> addPlayers(field.get(null), players));
					log.debug("Lo-fi audio: players in static {}.{}", clazz.getName(), field.getName());
					continue;
				}

				// One level deeper, e.g. the sound system thread object holding an array of players
				List<Field> nested = new ArrayList<>();
				for (Field inner : declaredFields(field.getType()))
					if (!Modifier.isStatic(inner.getModifiers()) && holdsPlayers(inner.getType(), playerClass))
						nested.add(inner);
				if (nested.isEmpty())
					continue;

				field.setAccessible(true);
				for (Field inner : nested) {
					inner.setAccessible(true);
					playerSources.add(players -> {
						Object holder = field.get(null);
						if (holder != null)
							addPlayers(inner.get(holder), players);
					});
					log.debug(
						"Lo-fi audio: players in {}.{} -> {}",
						clazz.getName(), field.getName(), inner.getName()
					);
				}
			}
		}

		log.info("Lo-fi audio found audio player {} with {} ways to reach it", playerClass.getName(), playerSources.size());
	}

	private static boolean holdsPlayers(Class<?> type, Class<?> playerClass) {
		if (type.isArray())
			type = type.getComponentType();
		// The field may be typed as the player's superclass, as the game's own mixer base class is
		return !type.isPrimitive() && type != Object.class && type.isAssignableFrom(playerClass);
	}

	private static void addPlayers(Object value, Set<Object> players) {
		if (value == null)
			return;
		if (value.getClass().isArray()) {
			for (int i = 0, n = Array.getLength(value); i < n; i++)
				addPlayers(Array.get(value, i), players);
		} else if (hasLineField(value.getClass())) {
			players.add(value);
		}
	}

	private static boolean hasLineField(Class<?> clazz) {
		for (Field field : declaredFields(clazz))
			if (field.getType() == SourceDataLine.class)
				return true;
		return false;
	}

	private static Field[] declaredFields(Class<?> clazz) {
		try {
			return clazz.getDeclaredFields();
		} catch (Throwable ex) {
			// Classes that can't be linked, e.g. referencing optional dependencies
			return new Field[0];
		}
	}

	/**
	 * Loads, without initializing, every top-level class shipped in the client jar. The game's classes live in the
	 * default package.
	 */
	private List<Class<?>> loadClientClasses(ClassLoader loader) throws Exception {
		URL location = client.getClass().getProtectionDomain().getCodeSource().getLocation();
		List<Class<?>> classes = new ArrayList<>();
		try (JarFile jar = new JarFile(new java.io.File(location.toURI()))) {
			Enumeration<JarEntry> entries = jar.entries();
			while (entries.hasMoreElements()) {
				String name = entries.nextElement().getName();
				if (!name.endsWith(".class") || name.contains("/"))
					continue;
				try {
					classes.add(Class.forName(name.substring(0, name.length() - 6), false, loader));
				} catch (Throwable ignored) {
					// Not loadable on its own, and so not one of the audio classes
				}
			}
		}
		return classes;
	}
}
