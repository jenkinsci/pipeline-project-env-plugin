package io.jenkins.plugins.projectenv.proc;

import hudson.Launcher.ProcStarter;
import hudson.Proc;
import io.jenkins.plugins.projectenv.context.StepContextHelper;
import org.jenkinsci.plugins.workflow.steps.StepContext;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public final class ProcHelper {

    private ProcHelper() {
        // noop
    }

    public static String executeAndGetStdOut(StepContext context, String... commands) throws Exception {
        ProcResult procResult = execute(context, commands);
        if (procResult.exitCode() == 0) {
            return procResult.stdOutput();
        } else {
            return null;
        }
    }

    public static ProcResult execute(StepContext context, String... commands) throws Exception {
        try (ByteArrayOutputStream stdOutInputStream = new ByteArrayOutputStream()) {

            ProcStarter procStarter = StepContextHelper.getLauncher(context)
                    .launch()
                    .cmds(commands)
                    .envs(StepContextHelper.getEnvVars(context))
                    .pwd(StepContextHelper.getWorkspace(context));

            Thread stdErrThread;
            int exitCode;
            try (PipedInputStream stdErrInputStream = new PipedInputStream();
                 BufferedOutputStream stdErrOutputStream = new BufferedOutputStream(new PipedOutputStream(stdErrInputStream))) {

                Proc process = procStarter
                        .stdout(stdOutInputStream)
                        .stderr(stdErrOutputStream)
                        .start();

                stdErrThread = attachStdErrLoggingThread(stdErrInputStream, context);
                exitCode = process.join();
            }
            stdErrThread.join();

            return ProcResult.builder()
                    .exitCode(exitCode)
                    .stdOutput(stdOutInputStream.toString(StandardCharsets.UTF_8))
                    .build();
        }
    }

    private static Thread attachStdErrLoggingThread(InputStream stdErrInputStream, StepContext context) throws Exception {
        PrintStream logger = StepContextHelper.getTaskListener(context).getLogger();

        Thread thread = new Thread(() -> {
            try (Scanner scanner = new Scanner(stdErrInputStream, StandardCharsets.UTF_8)) {
                while (scanner.hasNextLine()) {
                    logger.println(scanner.nextLine());
                }
            }
        });
        thread.start();

        return thread;
    }

}
