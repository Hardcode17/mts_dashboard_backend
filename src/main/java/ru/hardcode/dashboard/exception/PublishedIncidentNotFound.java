package ru.hardcode.dashboard.exception;

public class PublishedIncidentNotFound extends AppException {
    private final long revision;
    private final long eventSeq;

    public PublishedIncidentNotFound(long revision, long eventSeq) {
        super(AppErrors.INCIDENT_NOT_FOUND);
        this.revision = revision;
        this.eventSeq = eventSeq;
    }
    public long revision() { return revision; }
    public long eventSeq() { return eventSeq; }
}
