package ru.hardcode.dashboard.service;

/**
 * One notification channel of a coordinator (KAN-36 connections). The adapter hands the
 * message to the channel and reports the outcome signal it could observe (KAN-35):
 * {@link Signal#SENT} the channel accepted the message, {@link Signal#DELIVERED} the channel
 * confirmed delivery, {@link Signal#READ_IN_CHANNEL} the channel reported the recipient read
 * it there, {@link Signal#UNKNOWN} no signal is available. A failed attempt throws
 * {@link NotificationChannelFailure} with a sanitized reason that never contains addresses,
 * tokens or URLs.
 */
public interface NotificationChannelAdapter {

	Signal send(Destination destination, Message message);

	enum Signal {
		SENT,
		DELIVERED,
		READ_IN_CHANNEL,
		UNKNOWN
	}

	record Destination(long connectionId, String channel, String address, String secretRef) {
		@Override
		public String toString() {
			return "Destination[connectionId=%d, channel=%s]".formatted(connectionId, channel);
		}
	}

	record Message(String title, String body) {
	}
}
