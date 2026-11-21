package ru.hardcode.dashboard.dto;

/**
 * The stand source an evidence reference points to. {@code SCENARIO} is the explicit
 * demo mapping itself, the other values are the controlled stand sources.
 */
public enum McpEvidenceSource {
	SCENARIO,
	ITSM,
	LOG,
	DATABASE
}
