package io.github.aiarchguard.platform.scanjob.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class MailboxRunnerCancellationAdapter implements RunnerCancellationPort {
    private final Path cancellations;
    MailboxRunnerCancellationAdapter(@Value("${archguard.runner.mailbox:./runner-mailbox}") String mailbox) {
        this.cancellations=Path.of(mailbox).toAbsolutePath().normalize().resolve("cancellations");
    }
    @Override public void request(UUID jobId,UUID attemptToken) {
        if(attemptToken==null)return;
        try { Files.createDirectories(cancellations); Files.createFile(cancellations.resolve(jobId+"-"+attemptToken+".cancel")); }
        catch(IOException ignored) { /* The lease guarantees eventual recovery if signaling fails. */ }
    }
}
