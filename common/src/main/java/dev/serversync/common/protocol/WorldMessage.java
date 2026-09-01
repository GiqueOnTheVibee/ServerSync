package dev.serversync.common.protocol;

public sealed interface WorldMessage permits WorldBlockIntent, WorldBlockCommit {
}
