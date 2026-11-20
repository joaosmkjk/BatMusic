# BATBOOST X 3.0 — auditoria do código-fonte

Data: 20/11/2026  
Base: pacote `BatMusic-source-review.tar.gz` enviado pelo usuário.  
Escopo: `BatAudioProcessor`, `LookaheadTruePeakLimiter`, `AudioEngine`, `PlaybackService`, interface BAT AUDIO, presets e harness JVM.

## 1. Resumo executivo

O BATBOOST não estava sem processamento: o caminho PCM já aplicava ganho linear, filtros biquad e um limitador com lookahead. O defeito mais relevante para a reclamação de volume estava na configuração padrão do compressor: ele era ligado por padrão em `BatAudioSettings`, `PlaybackService`, `MainActivity` e nas leituras de preferências. O compressor podia retirar mais ganho do que o BATGAIN acrescentava.

Para uma senoide próxima de 0 dBFS, com threshold de −12 dB e ratio 3:1, a redução estática ideal aproxima-se de 8 dB. O BATGAIN em 200% adiciona +6,0206 dB. Logo, a combinação pode terminar abaixo do nível original antes mesmo da atuação do limiter. No ensaio comparativo adicionado ao harness, a saída transparente (compressor desligado) ficou **2,127454 dB acima** da saída com o compressor antigo ligado, para o sinal sintético especificado no teste.

Essa é uma explicação técnica forte para a experiência relatada, mas não substitui uma escuta A/B no aparelho.

## 2. Caminho de áudio observado

`PlaybackService.onCreate()` cria um `DefaultRenderersFactory` e substitui `buildAudioSink()` por `DefaultAudioSink.Builder`, registrando `audioEngine.processor` via `setAudioProcessors(arrayOf(...))`. A versão declarada do Media3 é **1.5.1**.

No `BatAudioProcessor`, o caminho de sinal é:

1. leitura de PCM16 ou PCM float;
2. EQ e Bass Boost, quando ativos;
3. pré-ganho e compensação de headroom heurística;
4. compressor, se habilitado;
5. ganho BATGAIN (100–200%, ou 0 a +6,0206 dB) combinado com makeup do compressor;
6. `LookaheadTruePeakLimiter`;
7. conversão para PCM de saída.

Não foram encontradas chamadas explícitas no código-fonte para `setPlaybackSpeed`, skip-silence ou configuração explícita de offload. A ordem interna completa em relação a processadores internos do `DefaultAudioSink` não foi validada contra o bytecode da dependência nesta execução; não se deve inferir uma ordem não documentada apenas a partir de `setAudioProcessors`.

## 3. Divergências entre a validação antiga e o código atual

O documento `BATBOOST_VALIDATION.md` descreve um limitador antigo de pico amostral sem true-peak detector/lookahead. O código-fonte fornecido já contém `LookaheadTruePeakLimiter`, com:

- lookahead de 1 ms;
- interpolador de detecção 4×, 16 taps;
- janela de pico por deque;
- canais linkados;
- drenagem de cauda em `onQueueEndOfStream`;
- reset em `onFlush`.

Assim, as conclusões antigas sobre a inexistência de lookahead e detector true-peak não descrevem o código-fonte entregue. A validação anterior também registra THD+N de −57,053177 dB, enquanto a execução isolada do harness atual, com stubs mínimos das APIs Media3, mediu −83,021454 dB para o caso de seno 1 kHz especificado. Os números não são diretamente intercambiáveis: o harness isolado não executa o sink real nem substitui um teste Gradle/Media3 completo.

## 4. Problemas confirmados no código entregue

### 4.1 Compressor padrão podia cancelar BATGAIN

`compressorEnabled` era `true` nos defaults de `BatAudioSettings`, `PlaybackService`, `MainActivity` e na leitura de preferências. A redução do compressor é aplicada antes do ganho BATGAIN. O makeup padrão era 0 dB. Portanto, a redução podia ser maior que os +6,0206 dB máximos de BATGAIN.

**Correção aplicada nesta cópia:** compressor desligado por padrão; migração de preferências para schema 3 que desliga a compressão herdada uma vez. O usuário pode reativá-la manualmente. Presets antigos continuam aceitando a ausência do novo campo de release do limiter.

### 4.2 Release do limiter estava acoplado ao compressor

O setter de `BatAudioSettings` e a criação do limiter usavam `compressorReleaseMs`. Isso tornava o controle do compressor também responsável pela recuperação do limiter.

**Correção aplicada:** novo campo `limiterReleaseMs`, persistido separadamente, enviado pelo comando da sessão, salvo em presets e exposto na interface. O release do limiter agora é independente.

### 4.3 A interface não mostrava a redução de ganho

A UI mostrava pico de saída e atividade do limiter, mas não a redução efetiva em dB nem níveis RMS pré/pós processamento.

**Correção aplicada:** o limiter passa a acumular o menor ganho aplicado no bloco e publicar a redução máxima em dB; o compressor publica a redução máxima calculada no bloco. Também são publicados pico pré-limiter e RMS pré/pós-limiter. O `AudioEngine` e a sessão expõem as medições e a UI as apresenta.

### 4.4 Rótulo de BATGAIN era impreciso

A interface chamava o controle de “BAT Boost” e mostrava porcentagem sem converter para decibéis, além de afirmar que não elevava o volume do sistema.

**Correção aplicada:** painel passa a identificar o ganho de sinal antes do limiter e mostra 0,00 a +6,02 dB.

### 4.5 Acesso PCM criava views temporárias

A leitura de PCM usava `asIntBuffer()`/`asShortBuffer()` e a captura do visualizador criava views adicionais.

**Correção aplicada:** leitura absoluta diretamente do `ByteBuffer`, preservando a posição inicial, evitando os wrappers dessas views.

### 4.6 Quantização PCM16

A saída processada usava truncamento e multiplicador 32767.

**Correção aplicada:** arredondamento para o inteiro mais próximo, escala 32768 e saturação ao intervalo PCM16. Dither TPDF ainda não foi implementado nem validado.

## 5. Medições disponíveis nesta execução

O harness de áudio foi compilado e executado com `kotlinc`, usando stubs mínimos para `AudioProcessor`/`BaseAudioProcessor` e JUnit, porque o ambiente não tinha Android SDK nem distribuição Gradle local. Os resultados abaixo são úteis para verificar o núcleo DSP, mas **não são aprovação de integração Android/Media3**.

| Medição do harness isolado | Resultado |
|---|---:|
| Erro máximo do EQ | 0,028280 dB |
| Erro médio do EQ | 0,003884 dB |
| Bypass float bit-exato | verdadeiro |
| Bypass PCM16 bit-exato | verdadeiro |
| True peak 8×, seno 1 kHz | 0,929900 |
| True peak 8×, seno fs/4 fase 45° | 0,929806 |
| True peak 8×, onda quadrada | 0,950103 |
| Maior resultado observado na varredura de taxas | 0,951043 |
| True peak 8×, estéreo linkado | 0,929900 |
| Erro de ganho BATGAIN 200% | −0,000001 dB |
| THD+N, seno 1 kHz | −83,021454 dB |
| Latência declarada pelo harness | 52 / 55 / 103 amostras em 44,1 / 48 / 96 kHz |
| Última amostra preservada no EOS | verdadeiro |
| Compressor antigo vs. caminho transparente | caminho transparente +2,127454 dB |

A execução isolada também mediu aproximadamente **1096 bytes alocados por bloco de 256 frames** após a alteração, contra 1152 bytes no mesmo harness antes da mudança. O acesso PCM deixou de criar as views temporárias, mas a meta de zero alocação não foi atingida. O uso de `BaseAudioProcessor.replaceOutputBuffer()` e a troca/entrega do buffer de saída precisam ser tratados como parte da análise de alocação; não declarar zero alocação com os dados atuais.

## 6. Itens ainda não concluídos

- Harness completo executado pelo Gradle com Media3 real.
- Compilação de `app:assembleDebug` e `testDebugUnitTest`.
- Dither TPDF para PCM16 processado.
- Referência independente de true peak 8× comparada contra o limiter em todos os sinais, taxas e fases exigidos.
- Testes de resposta do compressor em 5 pontos e constantes de tempo.
- FFT fora da thread de áudio; o visualizador ainda usa DFT direta.
- Medição de RMS, pico e true peak pré/pós em buffers atômicos/lock-free.
- Zero alocações em `queueInput`; a medição atual não passa.
- Testes de CPU, underruns, compatibilidade de PCM float e estabilidade prolongada em aparelhos reais.
- Escuta A/B com o mesmo aparelho, fone e arquivos do usuário.

## 7. Bloqueio de ambiente

`./gradlew testDebugUnitTest --no-daemon` não chegou a executar o Gradle: o wrapper tentou baixar Gradle 8.9 e falhou com `UnknownHostException: services.gradle.org`. O caminho de Android SDK em `local.properties` (`/home/joaosmkjk/Android/Sdk`) não existe neste ambiente. Nenhum APK foi produzido aqui.
