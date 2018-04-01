package net.blay09.mods.waystones;

import com.google.common.collect.Maps;
import net.blay09.mods.waystones.block.BlockWaystone;
import net.blay09.mods.waystones.block.TileWaystone;
import net.blay09.mods.waystones.network.message.MessageTeleportEffect;
import net.blay09.mods.waystones.network.message.MessageWaystones;
import net.blay09.mods.waystones.network.NetworkHandler;
import net.blay09.mods.waystones.util.WaystoneEntry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.MobEffects;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.play.server.SPacketEntityEffect;
import net.minecraft.network.play.server.SPacketRespawn;
import net.minecraft.potion.PotionEffect;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.network.NetworkRegistry;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Map;

public class WaystoneManager {

	private static final Map<String, WaystoneEntry> serverWaystones = Maps.newHashMap();
	private static final Map<String, WaystoneEntry> knownWaystones = Maps.newHashMap();

	public static void activateWaystone(EntityPlayer player, TileWaystone waystone) {
		WaystoneEntry serverWaystone = getServerWaystone(waystone.getWaystoneName());
		if(serverWaystone != null) {
			PlayerWaystoneData.setLastServerWaystone(player, serverWaystone);
			sendPlayerWaystones(player);
			return;
		}
		PlayerWaystoneData.resetLastServerWaystone(player);
		removePlayerWaystone(player, new WaystoneEntry(waystone));
		addPlayerWaystone(player, waystone);
		sendPlayerWaystones(player);
	}

	public static void sendPlayerWaystones(EntityPlayer player) {
		if (player instanceof EntityPlayerMP) {
			PlayerWaystoneData waystoneData = PlayerWaystoneData.fromPlayer(player);
			NetworkHandler.channel.sendTo(new MessageWaystones(waystoneData.getWaystones(), getServerWaystones().toArray(new WaystoneEntry[getServerWaystones().size()]), waystoneData.getLastServerWaystoneName(), waystoneData.getLastFreeWarp(), waystoneData.getLastWarpStoneUse()), (EntityPlayerMP) player);
		}
	}

	public static void addPlayerWaystone(EntityPlayer player, TileWaystone waystone) {
		NBTTagCompound tagCompound = PlayerWaystoneData.getOrCreateWaystonesTag(player);
		NBTTagList tagList = tagCompound.getTagList(PlayerWaystoneData.WAYSTONE_LIST, Constants.NBT.TAG_COMPOUND);
		tagList.appendTag(new WaystoneEntry(waystone).writeToNBT());
		tagCompound.setTag(PlayerWaystoneData.WAYSTONE_LIST, tagList);
	}

	public static boolean removePlayerWaystone(EntityPlayer player, WaystoneEntry waystone) {
		NBTTagCompound tagCompound = PlayerWaystoneData.getWaystonesTag(player);
		NBTTagList tagList = tagCompound.getTagList(PlayerWaystoneData.WAYSTONE_LIST, Constants.NBT.TAG_COMPOUND);
		for (int i = 0; i < tagList.tagCount(); i++) {
			NBTTagCompound entryCompound = tagList.getCompoundTagAt(i);
			if (WaystoneEntry.read(entryCompound).equals(waystone)) {
				tagList.removeTag(i);
				return true;
			}
		}
		return false;
	}

	public static boolean checkAndUpdateWaystone(EntityPlayer player, WaystoneEntry waystone) {
		WaystoneEntry serverEntry = getServerWaystone(waystone.getName());
		if(serverEntry != null) {
			if(getWaystoneInWorld(serverEntry) == null) {
				removeServerWaystone(serverEntry);
				return false;
			}
			if(removePlayerWaystone(player, waystone)) {
				sendPlayerWaystones(player);
			}
			return true;
		}
		NBTTagCompound tagCompound = PlayerWaystoneData.getWaystonesTag(player);
		NBTTagList tagList = tagCompound.getTagList(PlayerWaystoneData.WAYSTONE_LIST, Constants.NBT.TAG_COMPOUND);
		for (int i = 0; i < tagList.tagCount(); i++) {
			NBTTagCompound entryCompound = tagList.getCompoundTagAt(i);
			if (WaystoneEntry.read(entryCompound).equals(waystone)) {
				TileWaystone tileEntity = getWaystoneInWorld(waystone);
				if (tileEntity != null) {
					if (!entryCompound.getString("Name").equals(tileEntity.getWaystoneName())) {
						entryCompound.setString("Name", tileEntity.getWaystoneName());
						sendPlayerWaystones(player);
					}
					return true;
				} else {
					removePlayerWaystone(player, waystone);
					sendPlayerWaystones(player);
				}
				return false;
			}
		}
		return false;
	}

	public static TileWaystone getWaystoneInWorld(WaystoneEntry waystone) {
		World targetWorld = DimensionManager.getWorld(waystone.getDimensionId());
		if(targetWorld == null) {
			DimensionManager.initDimension(waystone.getDimensionId());
			targetWorld = DimensionManager.getWorld(waystone.getDimensionId());
		}
		if(targetWorld != null) {
			TileEntity tileEntity = targetWorld.getTileEntity(waystone.getPos());
			if (tileEntity instanceof TileWaystone) {
				return (TileWaystone) tileEntity;
			}
		}
		return null;
	}

	public static boolean teleportToWaystone(EntityPlayer player, WaystoneEntry waystone) {
		if(!checkAndUpdateWaystone(player, waystone)) {
			TextComponentTranslation chatComponent = new TextComponentTranslation("waystones:waystoneBroken");
			chatComponent.getStyle().setColor(TextFormatting.RED);
			player.addChatComponentMessage(chatComponent);
			return false;
		}
		WaystoneEntry serverEntry = getServerWaystone(waystone.getName());
		World targetWorld = DimensionManager.getWorld(waystone.getDimensionId());
		EnumFacing facing = targetWorld.getBlockState(waystone.getPos()).getValue(BlockWaystone.FACING);
		BlockPos targetPos = waystone.getPos().offset(facing);
		boolean dimensionWarp = waystone.getDimensionId() != player.getEntityWorld().provider.getDimension();
		if (dimensionWarp && !(
			(serverEntry == null && Waystones.getConfig().interDimension) ||
			(serverEntry != null && Waystones.getConfig().globalInterDimension)
			)) {
			player.addChatComponentMessage(new TextComponentTranslation("waystones:noDimensionWarp"));
			return false;
		}
		sendTeleportEffect(player.worldObj, new BlockPos(player));
		player.addPotionEffect(new PotionEffect(MobEffects.BLINDNESS, 20, 3));
		if(dimensionWarp) {
			MinecraftServer server = player.worldObj.getMinecraftServer();
			if(server != null) {
				transferPlayerToDimension((EntityPlayerMP) player, waystone.getDimensionId(), server.getPlayerList());
			}
		}
		player.rotationYaw = getRotationYaw(facing);
		player.setPositionAndUpdate(targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);
		sendTeleportEffect(player.worldObj, targetPos);
		return true;
	}

	/**
	 *  Taken from CoFHCore's EntityHelper (https://github.com/CoFH/CoFHCore/blob/1.12/src/main/java/cofh/core/util/helpers/EntityHelper.java)
	 */
	private static void transferPlayerToDimension(EntityPlayerMP player, int dimension, PlayerList manager) {
		int oldDim = player.dimension;
		WorldServer oldWorld = manager.getServerInstance().worldServerForDimension(player.dimension);
		player.dimension = dimension;
		WorldServer newWorld = manager.getServerInstance().worldServerForDimension(player.dimension);
		player.connection.sendPacket(new SPacketRespawn(player.dimension, player.worldObj.getDifficulty(), player.worldObj.getWorldInfo().getTerrainType(), player.interactionManager.getGameType()));
		oldWorld.removeEntityDangerously(player);
		if (player.isBeingRidden()) {
			player.removePassengers();
		}
		if (player.isRiding()) {
			player.dismountRidingEntity();
		}
		player.isDead = false;
		transferEntityToWorld(player, oldWorld, newWorld);
		manager.preparePlayer(player, oldWorld);
		player.connection.setPlayerLocation(player.posX, player.posY, player.posZ, player.rotationYaw, player.rotationPitch);
		player.interactionManager.setWorld(newWorld);
		manager.updateTimeAndWeatherForPlayer(player, newWorld);
		manager.syncPlayerInventory(player);

		for (PotionEffect potioneffect : player.getActivePotionEffects()) {
			player.connection.sendPacket(new SPacketEntityEffect(player.getEntityId(), potioneffect));
		}
		FMLCommonHandler.instance().firePlayerChangedDimensionEvent(player, oldDim, dimension);
	}

	/**
	 * Taken from CoFHCore's EntityHelper (https://github.com/CoFH/CoFHCore/blob/1.12/src/main/java/cofh/core/util/helpers/EntityHelper.java)
	 */
	private static void transferEntityToWorld(Entity entity, WorldServer oldWorld, WorldServer newWorld) {
		WorldProvider oldWorldProvider = oldWorld.provider;
		WorldProvider newWorldProvider = newWorld.provider;
		double moveFactor = oldWorldProvider.getMovementFactor() / newWorldProvider.getMovementFactor();
		double x = entity.posX * moveFactor;
		double z = entity.posZ * moveFactor;

		oldWorld.theProfiler.startSection("placing");
		x = MathHelper.clamp_double(x, -29999872, 29999872);
		z = MathHelper.clamp_double(z, -29999872, 29999872);
		if (entity.isEntityAlive()) {
			entity.setLocationAndAngles(x, entity.posY, z, entity.rotationYaw, entity.rotationPitch);
			newWorld.spawnEntityInWorld(entity);
			newWorld.updateEntityWithOptionalForce(entity, false);
		}
		oldWorld.theProfiler.endSection();

		entity.setWorld(newWorld);
	}

	public static void sendTeleportEffect(World world, BlockPos pos) {
		NetworkHandler.channel.sendToAllAround(new MessageTeleportEffect(pos), new NetworkRegistry.TargetPoint(world.provider.getDimension(), pos.getX(), pos.getY(), pos.getZ(), 64));
	}

	public static float getRotationYaw(EnumFacing facing) {
		switch(facing) {
			case NORTH:
				return 180f;
			case SOUTH:
				return 0f;
			case WEST:
				return 90f;
			case EAST:
				return -90f;
		}
		return 0f;
	}

	public static void addServerWaystone(WaystoneEntry entry) {
		serverWaystones.put(entry.getName(), entry);
		WaystoneConfig.storeServerWaystones(Waystones.configuration, serverWaystones.values());
	}

	public static void removeServerWaystone(WaystoneEntry entry) {
		serverWaystones.remove(entry.getName());
		WaystoneConfig.storeServerWaystones(Waystones.configuration, serverWaystones.values());
	}

	public static void setServerWaystones(WaystoneEntry[] entries) {
		serverWaystones.clear();
		for(WaystoneEntry entry : entries) {
			serverWaystones.put(entry.getName(), entry);
		}
	}

	public static void setKnownWaystones(WaystoneEntry[] entries) {
		knownWaystones.clear();
		for(WaystoneEntry entry : entries) {
			knownWaystones.put(entry.getName(), entry);
		}
	}

	@Nullable
	public static WaystoneEntry getKnownWaystone(String name) {
		return knownWaystones.get(name);
	}

	public static Collection<WaystoneEntry> getServerWaystones() {
		return serverWaystones.values();
	}

	@Nullable
	public static WaystoneEntry getServerWaystone(String name) {
		return serverWaystones.get(name);
	}
}
