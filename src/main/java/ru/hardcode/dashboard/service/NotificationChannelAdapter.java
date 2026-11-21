package ru.hardcode.dashboard.service;

public interface NotificationChannelAdapter {

	String send(Destination destination, Message message);

	record Destination(long connectionId, String channel, String address, String secretRef) {
		@Override
		public String toString() {
			return "Destination[connectionId=%d, channel=%s]".formatted(connectionId, channel);
		}
	}

	record Message(String title, String body) {
	}
}
