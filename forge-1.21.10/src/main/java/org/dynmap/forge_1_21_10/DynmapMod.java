package org.dynmap.forge_1_21_10;

import java.io.File;

import org.apache.commons.lang3.tuple.Pair;
import org.dynmap.DynmapCommonAPI;
import org.dynmap.DynmapCommonAPIListener;
import org.dynmap.Log;
import org.dynmap.forge_1_21_10.DynmapPlugin.OurLog;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.eventbus.api.bus.EventBus;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod("dynmap")
public class DynmapMod
{
    // The instance of your mod that Forge uses.
    public static DynmapMod instance;
    public static BusGroup modBusGroup;

    // Says where the client and server 'proxy' code is loaded.
    public static Proxy proxy = DistExecutor.runForDist(() -> ClientProxy::new, () -> Proxy::new);
    
    public static DynmapPlugin plugin;
    public static File jarfile;
    public static String ver;
    public static boolean useforcedchunks;

    public class APICallback extends DynmapCommonAPIListener {
        @Override
        public void apiListenerAdded() {
            if(plugin == null) {
                plugin = proxy.startServer(server);
            }
        }
        @Override
        public void apiEnabled(DynmapCommonAPI api) {
        }
    } 
    
    public DynmapMod(FMLJavaModLoadingContext context) {
    	instance = this;
    	
        modBusGroup = context.getModBusGroup();

        // Register the commonSetup method for modloading
        FMLCommonSetupEvent.getBus(modBusGroup).addListener(this::setup);

        MinecraftForge.EVENT_BUS.register(this);

        context.registerExtensionPoint(IExtensionPoint.DisplayTest.class, 
        		()->new IExtensionPoint.DisplayTest(()->IExtensionPoint.DisplayTest.IGNORESERVERONLY, (remote, isServer)-> true));

        Log.setLogger(new OurLog());      
        org.dynmap.modsupport.ModSupportImpl.init();
    }
    
    public void setup(final FMLCommonSetupEvent event)
    {
    	//TOOO
        jarfile = ModList.get().getModFileById("dynmap").getFile().getFilePath().toFile();

        ver = ModList.get().getModContainerById("dynmap").get().getModInfo().getVersion().toString();

        //// Load configuration file - use suggested (config/WesterosBlocks.cfg)
        //Configuration cfg = new Configuration(event.getSuggestedConfigurationFile());
        //try {
        //    cfg.load();
        //    
        //    useforcedchunks = cfg.get("Settings",  "UseForcedChunks", true).getBoolean(true);
        //}
        //finally
        //{
        //    cfg.save();
        //}
    }

    private MinecraftServer server;
    
    @SubscribeEvent
    public void onServerStarting(ServerAboutToStartEvent event) {
        server = event.getServer();
        if(plugin == null)
            plugin = proxy.startServer(server);
		plugin.onStarting(server.getCommands().getDispatcher());
	}
    
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        DynmapCommonAPIListener.register(new APICallback()); 
        plugin.serverStarted();
    }

    @SubscribeEvent
    public void serverStopping(ServerStoppingEvent event)
    {
    	proxy.stopServer(plugin);
    	plugin = null;
    }
}
