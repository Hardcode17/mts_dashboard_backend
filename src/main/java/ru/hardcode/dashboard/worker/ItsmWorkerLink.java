package ru.hardcode.dashboard.worker;

/**
 * Narrow view of the KAN-54 runner used by the worker service and by intake. Kept as an
 * interface so the service and the runner can be wired without a constructor cycle; it exists
 * only when the worker is configured.
 */
public interface ItsmWorkerLink {

	/**
	 * Asks the dispatcher to look for work; called after a new batch is committed. Must never
	 * fail the caller.
	 */
	void wake();
}
