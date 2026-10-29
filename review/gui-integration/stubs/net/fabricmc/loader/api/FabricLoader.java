package net.fabricmc.loader.api;
import java.util.Optional;
public class FabricLoader {public static FabricLoader getInstance(){return new FabricLoader();} public java.nio.file.Path getConfigDir(){return java.nio.file.Path.of("review/gui-integration/config");}
 public net.fabricmc.api.EnvType getEnvironmentType(){return net.fabricmc.api.EnvType.CLIENT;}
 public boolean isModLoaded(String id){return "voicechat".equals(id);}
 public Optional<ModContainer> getModContainer(String id){return Optional.of(new ModContainer());}}