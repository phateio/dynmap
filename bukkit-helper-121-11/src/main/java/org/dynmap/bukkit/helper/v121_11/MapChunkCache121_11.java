package org.dynmap.bukkit.helper.v121_11;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.dynmap.DynmapChunk;
import org.dynmap.Log;
import org.dynmap.bukkit.helper.BukkitWorld;
import org.dynmap.common.BiomeMap;
import org.dynmap.common.chunk.GenericChunk;
import org.dynmap.common.chunk.GenericChunkCache;
import org.dynmap.common.chunk.GenericMapChunkCache;

import java.lang.reflect.Method;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Container for managing chunks - dependent upon using chunk snapshots, since rendering is off server thread
 */
public class MapChunkCache121_11 extends GenericMapChunkCache {
	private World w;

	// CraftBukkit reflection support for Paper/Spigot compatibility
	private static Class<?> craftWorldClass;
	private static Class<?> craftServerClass;
	private static Method craftWorldGetHandle;
	private static Method craftWorldIsChunkLoaded;
	private static Method craftServerGetServer;
	private static boolean initialized = false;

	private static void initReflection() {
		if (initialized) return;
		initialized = true;

		// Try Paper's unversioned packages first, then fall back to Spigot's versioned packages
		String[] packagePrefixes = {
			"org.bukkit.craftbukkit",           // Paper 1.20.5+
			"org.bukkit.craftbukkit.v1_21_R7"   // Spigot 1.21.11
		};

		for (String prefix : packagePrefixes) {
			try {
				craftWorldClass = Class.forName(prefix + ".CraftWorld");
				craftServerClass = Class.forName(prefix + ".CraftServer");

				// Get methods
				craftWorldGetHandle = craftWorldClass.getMethod("getHandle");
				craftWorldIsChunkLoaded = craftWorldClass.getMethod("isChunkLoaded", int.class, int.class);
				craftServerGetServer = craftServerClass.getMethod("getServer");

				Log.info("[Dynmap] MapChunkCache using CraftBukkit package: " + prefix);
				return;
			} catch (ClassNotFoundException | NoSuchMethodException e) {
				// Try next prefix
			}
		}
		Log.severe("[Dynmap] MapChunkCache failed to find CraftBukkit classes!");
	}

	private ServerLevel getServerLevel(World world) {
		initReflection();
		try {
			return (ServerLevel) craftWorldGetHandle.invoke(world);
		} catch (Exception e) {
			Log.severe("Failed to get ServerLevel: " + e.getMessage());
			return null;
		}
	}

	private boolean isChunkLoaded(World world, int x, int z) {
		initReflection();
		try {
			return (Boolean) craftWorldIsChunkLoaded.invoke(world, x, z);
		} catch (Exception e) {
			Log.warning("Failed to check chunk loaded status: " + e.getMessage());
			return false;
		}
	}

	private MinecraftServer getMinecraftServer() {
		initReflection();
		try {
			return (MinecraftServer) craftServerGetServer.invoke(Bukkit.getServer());
		} catch (Exception e) {
			Log.severe("Failed to get MinecraftServer: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Construct empty cache
	 */
	public MapChunkCache121_11(GenericChunkCache cc) {
		super(cc);
	}

	@Override
	protected Supplier<GenericChunk> getLoadedChunkAsync(DynmapChunk chunk) {
		ServerLevel serverLevel = getServerLevel(w);
		MinecraftServer server = getMinecraftServer();
		if (serverLevel == null || server == null) {
			return () -> null;
		}

		CompletableFuture<Optional<SerializableChunkData>> chunkData = CompletableFuture.supplyAsync(() -> {
			LevelChunk c = serverLevel.getChunkIfLoaded(chunk.x, chunk.z);
			if (c == null || !c.loaded) {
				return Optional.empty();
			}
			return Optional.of(SerializableChunkData.copyOf(serverLevel, c));
		}, server);
		return () -> chunkData.join().map(SerializableChunkData::write).map(NBT.NBTCompound::new).map(this::parseChunkFromNBT).orElse(null);
	}

	protected GenericChunk getLoadedChunk(DynmapChunk chunk) {
		ServerLevel serverLevel = getServerLevel(w);
		if (serverLevel == null) return null;

		if (!isChunkLoaded(w, chunk.x, chunk.z)) return null;
		LevelChunk c = serverLevel.getChunkIfLoaded(chunk.x, chunk.z);
		if (c == null || !c.loaded) return null;
		SerializableChunkData chunkData = SerializableChunkData.copyOf(serverLevel, c);
		CompoundTag nbt = chunkData.write();
		return nbt != null ? parseChunkFromNBT(new NBT.NBTCompound(nbt)) : null;
	}

	@Override
	protected Supplier<GenericChunk> loadChunkAsync(DynmapChunk chunk) {
		ServerLevel serverLevel = getServerLevel(w);
		if (serverLevel == null) {
			return () -> null;
		}

		CompletableFuture<Optional<CompoundTag>> genericChunk = serverLevel.getChunkSource().chunkMap.read(new ChunkPos(chunk.x, chunk.z));
		return () -> genericChunk.join().map(NBT.NBTCompound::new).map(this::parseChunkFromNBT).orElse(null);
	}

	protected GenericChunk loadChunk(DynmapChunk chunk) {
		ServerLevel serverLevel = getServerLevel(w);
		if (serverLevel == null) return null;

		CompoundTag nbt = null;
		ChunkPos cc = new ChunkPos(chunk.x, chunk.z);
		GenericChunk gc = null;
		try {
			nbt = serverLevel
					.getChunkSource()
					.chunkMap
					.read(cc)
					.join().get();
		} catch (CancellationException cx) {
		} catch (NoSuchElementException snex) {
		}
		if (nbt != null) {
			gc = parseChunkFromNBT(new NBT.NBTCompound(nbt));
		}
		return gc;
	}

	public void setChunks(BukkitWorld dw, List<DynmapChunk> chunks) {
		this.w = dw.getWorld();
		super.setChunks(dw, chunks);
	}

	@Override
	public int getFoliageColor(BiomeMap bm, int[] colormap, int x, int z) {
		return bm.<Biome>getBiomeObject().map(Biome::getSpecialEffects).flatMap(BiomeSpecialEffects::foliageColorOverride).orElse(colormap[bm.biomeLookup()]);
	}

	@Override
	public int getGrassColor(BiomeMap bm, int[] colormap, int x, int z) {
		BiomeSpecialEffects effects = bm.<Biome>getBiomeObject().map(Biome::getSpecialEffects).orElse(null);
		if (effects == null) return colormap[bm.biomeLookup()];
		return effects.grassColorModifier().modifyColor(x, z, effects.grassColorOverride().orElse(colormap[bm.biomeLookup()]));
	}
}
