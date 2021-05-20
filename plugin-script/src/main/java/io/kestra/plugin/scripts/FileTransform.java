package io.kestra.plugin.scripts;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.Single;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.io.*;
import java.net.URI;
import java.util.Optional;
import javax.script.Bindings;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Transform ion format file from kestra with a groovy script.",
    description = "This allow you to transform the data previouly loaded by kestra as you need.\n\n" +
        "Take a ion format file from kestra and iterate row per row.\n" +
        "Each row will populate a `row` global variable, you need to alter this variable that will be saved on output file.\n" +
        "if you set the `row` to `null`, the row will be skipped\n"
)
public abstract class FileTransform extends AbstractScript implements RunnableTask<FileTransform.Output> {
    @NotNull
    @Schema(
        title = "Source file URI",
        description = "The file must an ion file generated by kestra"
    )
    @PluginProperty(dynamic = true)
    private String from;

    @Min(2)
    @NotNull
    @Schema(
        title = "Number of concurrent parrallels transform",
        description = "Take care that the order is **not respected** if you use parallelism"
    )
    @PluginProperty(dynamic = false)
    private Integer concurrent;

    protected FileTransform.Output run(RunContext runContext, String engineName) throws Exception {
        // temp out file
        URI from = new URI(runContext.render(this.from));
        File tempFile = File.createTempFile(this.getClass().getSimpleName().toLowerCase() + "_", ".trs");

        // prepare script
        ScriptEngineService.CompiledScript scripts = ScriptEngineService.scripts(
            runContext,
            engineName,
            generateScript(runContext),
            this.getClass().getClassLoader()
        );

        try (
            BufferedReader inputStream = new BufferedReader(new InputStreamReader(runContext.uriToInputStream(from)));
            OutputStream output = new FileOutputStream(tempFile);
        ) {
            Flowable<Object> flowable = Flowable
                .create(FileSerde.reader(inputStream), BackpressureStrategy.BUFFER);

            Flowable<Optional<Object>> sequential;

            if (this.concurrent != null) {
                sequential = flowable
                    .parallel(this.concurrent)
                    .runOn(Schedulers.io())
                    .map(this.convert(scripts))
                    .sequential();
            } else {
                sequential = flowable
                    .map(this.convert(scripts));
            }

            Single<Long> count = sequential
                .filter(Optional::isPresent)
                .map(Optional::get)
                .doOnNext(row -> FileSerde.write(output, row))
                .count();

            // metrics & finalize
            Long lineCount = count.blockingGet();
            runContext.metric(Counter.of("records", lineCount));

            output.flush();
        }

        return Output
            .builder()
            .uri(runContext.putTempFile(tempFile))
            .build();
    }

    protected Function<Object, Optional<Object>> convert(ScriptEngineService.CompiledScript script) {
        return row -> {
            Bindings bindings = script.getBindings().get();
            bindings.put("row", row);

            script.getScript().eval(bindings);

            return Optional.ofNullable(bindings.get("row"));
        };
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "URI of a temporary result file",
            description = "The file will be serialized as ion file."
        )
        private final URI uri;
    }
}
