package com.inf.farlands;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;

import com.inf.farlands.register.FarlandsRegister;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InfsFarlands implements ModInitializer {
	public static final String MOD_ID = "infs-farlands";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	static {
		FarlandsRegister.registerStatic();
	}

	@Override
	public void onInitialize() {
		// * Minecraft: Story Mode
		LOGGER.info("Nothing built can last forever, and every legend, no matter how great, fades with time.");
		FarlandsRegister.register();
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
