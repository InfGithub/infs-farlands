package com.inf.farlands.client;

import net.fabricmc.api.ClientModInitializer;
import com.inf.farlands.client.register.FarlandsRegister;

public class InfsFarlandsClient implements ClientModInitializer {

	static {
		FarlandsRegister.registerStatic();
	}

	@Override
	public void onInitializeClient() {
		FarlandsRegister.register();
	}
}