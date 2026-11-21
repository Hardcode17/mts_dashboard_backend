package ru.hardcode.dashboard.worker;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

/**
 * Identity of one worker process instance for the claim owner column (ownership fencing).
 */
record ItsmWorkerId(String value) {

	static ItsmWorkerId create() {
		return new ItsmWorkerId(hostName() + ":" + processId() + ":" + UUID.randomUUID());
	}

	private static String hostName() {
		try {
			return InetAddress.getLocalHost().getHostName();
		} catch (UnknownHostException exception) {
			return "localhost";
		}
	}

	private static String processId() {
		return ManagementFactory.getRuntimeMXBean().getName().split("@", 2)[0];
	}
}
