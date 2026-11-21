package ru.hardcode.dashboard.worker;

/**
 * Test access to the application's worker service so an IT can run an additional runner with a
 * different pool size against the same service and database.
 */
public final class ItsmWorkerServiceAccess {

	private ItsmWorkerServiceAccess() {
	}

	public static ItsmWorkerService runnerService(ItsmWorkerRunner runner) {
		return runner.workerService();
	}
}
