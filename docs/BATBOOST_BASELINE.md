# BATBOOST X 2.0 — Etapa 0: arquitetura e baseline

Data da inspeção: 2026-11-19  
Branch: `feature/batboost-x-2`  
HEAD observado: `604e9a469227809493d0f56b347181352492a5a1` (`baseline-batboost`)

## Escopo e integridade do checkout

Foi lido integralmente `CLAUDE.md` e `PROMPT_CLAUDE_CODE.md`. O terceiro documento solicitado, `docs/BATBOOST_AUDITORIA.md`, não existe neste checkout. A busca nominal em `/home/joaosmkjk`, `Downloads`, `Documentos` e `/tmp`, a busca de arquivos Markdown no projeto e a busca no histórico Git (`git log --all` e árvore do HEAD) não localizaram o relatório. Portanto não foi possível validar o texto, a evidência nem o contexto completo das hipóteses P4, P6, P8 e P10 do relatório.

A branch pedida continua ativa e aponta para o commit/tag `baseline-batboost`. O checkout já tinha alterações e arquivos não rastreados antes da auditoria, inclusive `PlaybackService.kt`, `app/build.gradle.kts`, fontes de áudio, `app/src/test`, APK e uma árvore FPlayAndroid. Também existem exclusões em `app/app/...`. Elas foram preservadas. Nesta continuação foram adicionados apenas este relatório atualizado, o harness em `app/src/test/java/com/batmusic/app/audio/BatAudioBaselineHarness.kt` e o runner `scripts/run-batboost-baseline.sh`. Nenhum arquivo de produção foi alterado. Nenhum commit foi feito.

## Arquitetura real encontrada

### Criação e ligação com Media3

`PlaybackService.onCreate()` cria um `DefaultRenderersFactory` anônimo e substitui `buildAudioSink`. O `DefaultAudioSink.Builder` recebe um único `AudioProcessor`, `audioEngine.processor` (`BatAudioProcessor`), além das opções de saída float e AudioTrack playback parameters passadas pelo factory. O `ExoPlayer` recebe esse factory e é entregue a um `MediaSession` pelo próprio `PlaybackService`, que estende `MediaSessionService`.

No Media3 1.5.1, a cadeia interna padrão do `DefaultAudioSink` concatena os processadores fornecidos pelo app, `SilenceSkippingAudioProcessor` e `SonicAudioProcessor`, nessa ordem. Assim, `BatAudioProcessor` processa o PCM antes do skip-silence e do Sonic. A inspeção foi feita no bytecode do AAR 1.5.1 em cache (`DefaultAudioSink$DefaultAudioProcessorChain`), não por inferência baseada apenas no nome da API.

### Processador, configurações e publicação

`AudioEngine` é a fachada: possui uma instância de `BatAudioProcessor`, mantém `currentSettings` `@Volatile` e encaminha configurações para a propriedade `settings` também `@Volatile` do processador. `BatAudioSettings` é um `data class` imutável e é construído no `PlaybackService.applyEffects()` a partir do estado dos controles. O canal de comando Media3 `SET_AUDIO_EFFECTS` atualiza esses controles; `audioABOriginal` desativa o processamento ao configurar `enabled = false`. Métricas e dados do visualizador são publicados em session extras.

`SavedAudioPreset` é construído ao salvar na `MainActivity`. `AudioPresetRepository` serializa presets JSON na preferência separada `batmusic_audio_presets`; a leitura valida limites com `coerceIn`. As configurações de efeitos do serviço usam a preferência `batmusic`; leitura e gravação estão em `PlaybackService`. A migração `preamp_db` → `preamp_db_float` está presente como fallback de leitura, com clamp de 0 a 24 dB.

### Processamento efetivo

O `BatAudioProcessor` aceita PCM 16-bit e float, passa formatos não suportados adiante como `NOT_SET` e configura estados por canal em `onFlush`. Com processamento desativado, copia os bytes para o buffer de saída e atualiza pico/visualizador. Ativado, converte o bloco para `FloatArray`, aplica biquads de graves/EQ, ganho/headroom heurístico, compressor com detector de pico e knee fixo de 6 dB, seguido de limiter instantâneo por pico amostral com ceiling 0,985 e suavização de ganho. A saída PCM16 usa conversão para inteiro por truncamento; não há dither. O processador não implementa `onQueueEndOfStream`; não há estado de lookahead ou cauda a esvaziar.

O EQ atual oferece dez frequências fixas, filtros peaking com Q implícito (não configurável) e low shelf fixo em 95 Hz. Coeficientes e estado do biquad são `Float`; alguns cálculos de coeficiente usam intermediários `Double` antes de converter para `Float`.

### Velocidade, skip-silence e offload

Não foram encontradas chamadas no código do app para `setPlaybackSpeed`, `PlaybackParameters` ou `setSkipSilenceEnabled`. Media3 ainda pode expor esses recursos por integração externa/controladores, mas isso não é configurado pela UI nem pelo `PlaybackService` neste checkout. O sink padrão contém Sonic e skip-silence mesmo que permaneçam inativos.

Não há configuração explícita de áudio offload no app ou no `DefaultAudioSink.Builder` usado. Isso não prova que qualquer formato ou versão/plataforma jamais use caminho offload: a decisão final depende da configuração e capacidades do sink/dispositivo. A presença de processador PCM também significa que efeitos não podem ser presumidos no caminho codificado/offload.

## Hipóteses do relatório e divergências

Como o arquivo-fonte do relatório está ausente, o resultado abaixo valida as afirmações técnicas correspondentes ao rótulo, mas não a formulação original do relatório:

| Hipótese | Verificação no código real | Resultado |
|---|---|---|
| P4 — zipper noise | Os novos settings são lidos por bloco. Ao detectar mudança, filtros e coeficientes são substituídos; não há rampas de ganho, EQ, bypass ou crossfade. | Risco confirmado para mudança de parâmetros; magnitude audível não medida. |
| P6 — custo e alocações | O harness mediu alocação de heap da thread em `queueInput` após 20.000 blocos de aquecimento. A leitura HotSpot/JDK 21 foi 0 bytes/bloco no ensaio repetido. O caminho ainda redimensiona `sampleBuffer` se receber um bloco maior que o aquecido e reconstrói filtros/coefs quando os settings mudam. O número do host não representa ART/Android. | Zero bytes/bloco medidos neste HotSpot, com configuração e bloco fixos após aquecimento. Não demonstra zero alocações em mudança de configuração, outros tamanhos ou Android. |
| P8 — biquad float32 | Estado e coeficientes persistentes do biquad são `Float`. O cálculo dos coeficientes mistura algumas operações `Double` com conversão final para `Float`. | Confirmado para o filtro em execução; não é um cálculo inteiramente float32 na preparação. |
| P10 — ordem frente ao Sonic | Media3 1.5.1 adiciona processadores do app antes do silence skipper e Sonic na cadeia interna do sink. | Confirmado: BatAudioProcessor está antes do Sonic e do skip-silence. |

Também divergem dos requisitos descritos em `CLAUDE.md` alguns detalhes observáveis no código: há conversão PCM16 por truncamento; Q do EQ é fixo; o limiter opera por pico amostral, não true peak; o compressor usa knee fixo; mudanças de parâmetros não têm rampa; o pipeline atual não mede RMS, true peak nem ganho reduzido. Essas observações são fotografia do estado atual, não proposta de implementação nesta etapa.

## Media3 e APIs de ciclo de vida

Dependências declaradas em `app/build.gradle.kts`: `media3-exoplayer:1.5.1` e `media3-session:1.5.1`. A inspeção de `media3-common:1.5.1` confirmou em `BaseAudioProcessor` os métodos protegidos `onQueueEndOfStream()` e `onFlush()`, e o método protegido final `replaceOutputBuffer(int)`. A classe oferece o hook de fim de stream, mas o `BatAudioProcessor` atual não o sobrescreve. `onFlush()` é sobrescrito e reinicializa estados do processador.

## Investigação do Gradle e execução reproduzível

O comando completo do projeto foi tentado novamente:

```text
./gradlew --offline :app:testDebugUnitTest --no-daemon
```

Resultado real: **não executado**. Gradle Wrapper 8.9 não conseguiu iniciar o daemon: `TcpIncomingConnector.accept` falhou ao abrir o socket servidor local, com `java.net.SocketException: Operation not permitted`. As tentativas com argumentos JVM vazios e com argumentos da JVM alinhados também falharam antes de compilar ou executar testes. A tentativa de criar/bindar um socket TCP em `127.0.0.1` pelo Python na mesma sessão falhou com `PermissionError: [Errno 1] Operation not permitted`; `/proc/self/status` informa `Seccomp: 2` e um filtro ativo.

Essas evidências apontam fortemente para a política de isolamento desta sessão como causa do socket negado. **Não é possível concluir que o problema seja exclusivo do sandbox**, porque não há execução de comandos fora dele nesta interface. Também não há evidência de que o ambiente normal do usuário falhe. Para decidir, execute fora do sandbox tanto um bind de loopback quanto `./gradlew :app:testDebugUnitTest --no-daemon --stacktrace` no mesmo checkout. A falha Gradle observada aqui não foi contornada para a execução da suíte.

Há dois testes JVM preexistentes de fila/restauração (`PlaybackQueueRestorerTest` e `QueueIntegrityPoliciesTest`). Foi criado o teste/harness de áudio `BatAudioBaselineTest`. Para executar a suíte inteira no ambiente de desenvolvimento normal, use `./gradlew :app:testDebugUnitTest`. Para isolar o harness, use `./gradlew :app:testDebugUnitTest --tests com.batmusic.app.audio.BatAudioBaselineTest`. Nesta sessão, onde o daemon Gradle não inicia, o runner alternativo `scripts/run-batboost-baseline.sh` compila o arquivo fonte atual do processador e o harness com Kotlin Compiler Embeddable 2.0.21, usa `android.jar` API 35, Media3 common 1.5.1 e JUnit 4.13.2 já cacheados, e executa `JUnitCore` diretamente. O script não reutiliza classes ou relatórios de `app/build`.

Execução alternativa realizada nesta continuação:

```text
scripts/run-batboost-baseline.sh
JUnit version 4.13.2
OK (1 test)
```

O comando compilou os dois fontes do harness/processador para diretório temporário e executou o teste. Os resultados impressos nessa execução são os registrados abaixo. A execução direta valida o harness e mede o processador JVM atual; ela não substitui uma execução da suíte Gradle completa.

## Metodologia e medições do processador atual

- Ambiente: OpenJDK 21.0.13.1, Kotlin compiler embeddable 2.0.21, Android SDK `android-35`, Media3 1.5.1, JUnit 4.13.2; execução JVM local (HotSpot), sem dispositivo Android.
- Fonte compilada: `app/src/main/java/com/batmusic/app/audio/BatAudioProcessor.kt` no checkout atual junto com o novo harness. Nenhum `.class` antigo foi usado como processador.
- PCM float mono, exceto onde indicado. Os blocos para resposta de frequência têm 512 frames; a medição de `queueInput` usa 256 frames a 48 kHz.
- Resposta EQ: cascata com ganhos `[3, 2, 1, 0, -1, 0.5, 1.5, -2, 2, -1] dB`, Q de referência 1 (o valor implícito no código atual), entrada senoidal de amplitude 0,1 por 0,5 s. Mede-se RMS de entrada/saída no quarto final do sinal. Referência independente calcula RBJ peaking em `double`. 31 frequências log-espaçadas entre 31 e 16.000 Hz, em 44,1/48/96 kHz (93 medições no total).
- Limiter: `enabled=true`, BATBOOST 200% (+6,0206 dB), compressor desativado, ceiling do código 0,985. Sinais de 24.576 amostras a 48 kHz: seno 1 kHz em 0 dBFS, seno fs/4 com fase de 45°, e onda quadrada clipada de 997 Hz. Redução de pico compara pico de saída com pico de entrada após o ganho BATBOOST.
- True peak: máximo da reconstrução 8×, usando 8 fases por amostra e interpolador sinc com janela Blackman de 64 taps; são ignoradas 32 amostras em cada borda. É uma estimativa offline implementada no harness, não certificação normativa de um medidor true-peak.
- THD+N: seno 1 kHz a 48 kHz, início em 2.016 amostras e janela de 22.560 amostras (470 ciclos coerentes); projeção síncrona remove média/fundamental e calcula potência residual sobre a potência fundamental. A janela anterior começava em 2.048 e tinha 469⅓ ciclos; controles lineares indicaram contaminação por não coerência, então seu resultado foi substituído.
- Latência: posição da primeira amostra não nula de um impulso de 0,5, com DSP ativado, ganho neutro, EQ/compressores sem atuação. Reporta a latência aparente do caso sem lookahead.
- Alocação e custo: `ThreadMXBean` do HotSpot mede bytes de heap da thread apenas ao redor de cada chamada `queueInput`; buffer de entrada é preparado fora da região cronometrada. 20.000 blocos de aquecimento e 10.000 blocos medidos, bloco mono de 256 frames a 48 kHz. O contador não mede alocações nativas e o resultado depende do JIT.

Resultados medidos na execução reproduzível mais recente do runner:

| Medição pedida | Resultado nesta etapa |
|---|---|
| EQ vs referência double | erro absoluto máximo **0,028280 dB**; médio **0,003884 dB**, na configuração e frequências listadas. |
| Bypass float | **bit-exato: sim**, comparação dos bits incluindo +0, −0 e NaN com payload. |
| Seno 1 kHz, 0 dBFS, saída após +6,0206 dB | pico amostral **0,985000**; true peak 8× **0,985350**; redução de pico **6,151875 dB**; amostras `|x| >= 1`: **0**. |
| Seno fs/4, fase 45° | entrada pico **0,707107**; pico amostral saída **0,985000**; true peak 8× **1,393010**; redução vs sinal com BATBOOST **3,141575 dB**; amostras `|x| >= 1`: **0**. |
| Quadrada clipada, 997 Hz | pico amostral saída **0,985000**; true peak 8× **1,260911**; redução de pico **6,151875 dB**; amostras `|x| >= 1`: **0**. |
| THD+N, seno de 1 kHz | **−57,053177 dB** na janela coerente corrigida, com redução de pico medida em 6,151875 dB. O valor inicial −55,829024 dB usava janela não coerente e não é mais a referência. |
| Latência aparente do impulso | **0 amostras**. O código atual não implementa lookahead. |
| Alocação em `queueInput` (HotSpot/JDK 21) | **0 bytes/bloco** após warm-up, 10.000 blocos medidos. Não extrapolar para ART, mudanças de settings ou aumento de bloco. |
| Tempo de `queueInput` (HotSpot/JDK 21) | **12,592975–12,753212 µs/bloco** em duas execuções do runner; referência de máquina host, não aparelho Android. |
| Suíte JVM preexistente | **Não executada**; Gradle falhou antes da fase de compilação. |

A resposta EQ medida ficou abaixo da meta proposta de 0,1 dB em duas execuções idênticas para esta combinação e referência, mas não aprova uma etapa nem todos os estados/formatos. O THD+N não atinge −60 dB. O true peak ultrapassa o ceiling 0,985 em fs/4 e na quadrada: as razões true-peak/ceiling são aproximadamente +3,01 dB e +2,15 dB, respectivamente. O pico amostral abaixo de 1,0 não impede sobre-elevação entre amostras. Esses são resultados medidos do baseline, não critérios relaxados.

Não foram medidos: formatos/saída real em aparelho, escuta, CPU e underruns em hardware, estabilidade longa, ou variação de alocações no ART. A contagem de alocações foi feita somente no HotSpot com blocos/settings fixos.

## Riscos técnicos para as próximas etapas

- A alteração instantânea de parâmetros pode causar zipper noise; o tamanho do artefato não foi medido.
- Os valores agora registrados são uma referência reproduzível deste harness host, mas não caracterizam desempenho de aparelho Android nem todos os settings. Critérios globais não devem ser considerados aprovados.
- O limiter atual não garante limite true peak entre amostras; ceiling por pico amostral não basta para inter-sample peaks.
- Processamento float depende do formato efetivamente selecionado pelo sink/dispositivo; PCM16 e float seguem conversões diferentes.
- A ordem atual antes do Sonic significa que alteração de velocidade/pitch ocorre depois do DSP. Não há controle de velocidade no app para medir a interação.
- Offload pode contornar processamento PCM; o comportamento prático precisa ser verificado por configuração/dispositivo antes de prometer efeitos em todos os formatos.
- A medição espectral atual é uma DFT direta de 16 bins e faz trabalho considerável fora do callback de áudio, no caminho de publicação periódico.
- Presets e preferências têm repositórios e esquemas separados; mudanças futuras precisam preservar os defaults e valores salvos já existentes.
- O checkout tem alterações preexistentes extensas e arquivos duplicados/excluídos em `app/app`; comparar resultados contra HEAD sem distinguir esse estado pode atribuir mudanças ao baseline incorretamente.

## Plano recomendado para Etapa 1 (sujeito à aprovação do Bruce)

1. Executar fora deste sandbox `./gradlew :app:testDebugUnitTest` e guardar o resultado limpo dos dois testes de fila e do novo teste de áudio.
2. Recuperar o relatório de auditoria original para comparar as afirmações exatas e atualizar P4/P6/P8/P10 sem reconstruí-las por suposição.
3. Manter os números deste harness como baseline host e, quando houver dispositivo, medir separadamente ART/CPU e comportamento do sink real; não misturar os ambientes.
4. Bruce revisar e aprovar o baseline e os critérios quantitativos antes de qualquer implementação da Etapa 1.
5. Com aprovação explícita, implementar BATGAIN/BAT EQ sobre classe e integração atuais, preservar A/B/persistência/presets e medir os portões antes de avançar.

Nenhuma implementação das Etapas 1–4 foi iniciada nesta etapa.
