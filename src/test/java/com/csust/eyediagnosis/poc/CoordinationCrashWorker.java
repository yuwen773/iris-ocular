package com.csust.eyediagnosis.poc;

final class CoordinationCrashWorker {
    public static void main(String[] args) throws Exception {
        try (var connection = CoordinationDb.connect(args[0])) {
            boolean afterCommit = Boolean.parseBoolean(args[2]);
            new CoordinationStore(connection).advance(args[1], false, !afterCommit, afterCommit);
        }
    }
}
