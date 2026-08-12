package me.criseda.autostopper.operational;

public record PreflightSummary(int healthyMappings, int degradedMappings) {
    public boolean healthy() {
        return degradedMappings == 0;
    }
}
