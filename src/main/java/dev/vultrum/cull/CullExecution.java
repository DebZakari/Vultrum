package dev.vultrum.cull;

/** Records CPU command-recording progress; a returned draw is the only proof that its phase completed. */
final class CullExecution {
    interface Commands {
        void prepare();
        void phase1();
        void phase2();
        void readback();
        /** Restore all commands, or only commands not drawn in phase 1, with an indirect-read barrier. */
        void recover(boolean phase1Drawn);
        void failed(RuntimeException failure);
    }

    enum Progress { UNTOUCHED, PHASE1_COMMANDS, DRAWING_PHASE1, PHASE1_DRAWN, PHASE2_COMMANDS, DRAWING_PHASE2, COMPLETE }

    private Progress progress = Progress.UNTOUCHED;

    Progress progress() { return progress; }

    void run(Commands commands, Runnable draw) {
        try {
            commands.prepare();
            progress = Progress.PHASE1_COMMANDS;
            commands.phase1();
            progress = Progress.DRAWING_PHASE1;
            draw.run();
            progress = Progress.PHASE1_DRAWN;
            // This includes ending the terrain pass and building Hi-Z. Recovery does not depend on Hi-Z.
            progress = Progress.PHASE2_COMMANDS;
            commands.phase2();
            commands.readback();
            progress = Progress.DRAWING_PHASE2;
            draw.run();
            progress = Progress.COMPLETE;
        } catch (RuntimeException failure) {
            commands.failed(failure);
            if (progress == Progress.DRAWING_PHASE1 || progress == Progress.DRAWING_PHASE2) throw failure;
            if (progress != Progress.UNTOUCHED) {
                try {
                    commands.recover(progress == Progress.PHASE1_DRAWN || progress == Progress.PHASE2_COMMANDS);
                } catch (RuntimeException recoveryFailure) {
                    failure.addSuppressed(recoveryFailure);
                    throw failure;
                }
            }
            // Outside the catch: an original-draw failure is never mistaken for a cull failure or retried.
            draw.run();
        }
    }
}
