# BATBOOST X 3.0 — resultados da execução de desenvolvimento

Data: 20/11/2026  
Status: **implementação parcial; não declarar versão 3.0 pronta**.

## Alterações implementadas nesta cópia

### Fundação / BATGAIN

- Compressor passa a vir desligado por padrão.
- Migração de preferências `batboost_schema_version=3`: desliga uma vez o compressor herdado, pois o antigo default não podia ser distinguido de uma escolha manual persistida. O usuário pode reativá-lo depois.
- BATGAIN é apresentado em dB (100% = 0,00 dB; 200% = +6,02 dB), em vez de apenas porcentagem.
- A leitura PCM usa acessos absolutos no `ByteBuffer` para evitar a criação de views `IntBuffer`/`ShortBuffer` no processamento normal e na captura do visualizador.
- PCM16 processado passa a usar arredondamento e saturação, em vez de truncamento simples.

### Dinâmica / BATLIMITER

- Release do limiter separado do release do compressor.
- Novo parâmetro `limiterReleaseMs`, persistido nas preferências e nos presets, enviado pelo comando de sessão e exposto na tela BAT AUDIO.
- Medição de redução de ganho do limiter em dB, baseada no menor ganho efetivamente aplicado durante o bloco.
- Medição de redução máxima do compressor em dB, baseada no gain reduction calculado por bloco.
- RMS pré e pós-limiter e pico pré-limiter publicados para a UI.

### Interface / BAT ANALYZER

- A tela mostra redução de ganho do limiter e do compressor, além do pico de saída.
- O painel BATGAIN explica que o ganho é aplicado ao sinal PCM antes do limiter.
- Removida a afirmação vaga de “compressão suave, headroom automático” como se isso fosse garantia mensurada.

### Testes

- Harness estendido para medir o efeito do compressor antigo e verificar que o default novo vem desligado.
- O harness de áudio foi compilado com `kotlinc` e executado usando stubs mínimos para as classes Media3/JUnit, não a dependência Android real.

## Resultados medidos no harness isolado

| Métrica | Resultado |
|---|---:|
| Erro máximo do EQ | 0,028280 dB |
| Erro médio do EQ | 0,003884 dB |
| Bypass PCM float | bit-exato |
| Bypass PCM16 | bit-exato |
| True peak 8× — seno 1 kHz | 0,929900 |
| True peak 8× — fs/4 fase 45° | 0,929806 |
| True peak 8× — onda quadrada | 0,950103 |
| Maior true peak da varredura reportada | 0,951043 |
| True peak 8× — estéreo linkado | 0,929900 |
| Erro de ganho a 200% | −0,000001 dB |
| THD+N do seno 1 kHz | −83,021454 dB |
| Latência (44,1 / 48 / 96 kHz) | 52 / 55 / 103 amostras |
| Última amostra após EOS | preservada |
| Default do compressor | desligado |
| Saída transparente acima da saída com compressor antigo | +2,127454 dB no sinal diagnóstico |
| Alocação medida em `queueInput` | 1096,09 bytes/bloco no harness com stubs |

O resultado de alocação não cumpre a meta zero. A redução de 1152 para 1096 bytes/bloco ocorreu após retirar views temporárias de PCM; a maior parte restante aparenta estar associada ao ciclo de buffers do `BaseAudioProcessor`/harness. A solução exige investigar o contrato de buffer do Media3 real antes de redesenhar a integração.

## Portões ainda bloqueados

- Não houve `./gradlew testDebugUnitTest` nem `assembleDebug`: o wrapper tentou baixar Gradle 8.9 e falhou com `UnknownHostException: services.gradle.org`.
- Não há Android SDK neste ambiente no caminho declarado pelo `local.properties`.
- Os arquivos de UI, serviço, persistência e presets foram editados, mas não passaram por compilação Android/Compose.
- Não foi feito teste de reprodução no aparelho nem escuta A/B.
- O limiter ainda precisa de validação independente com referência 8×/8 fases e sinais/fases exigidos; os números listados são do harness fornecido, não uma certificação normativa.
- Dither TPDF ainda não implementado.
- True peak de saída independente/validado e análise de headroom em todo o pipeline ainda não implementados; o visualizador ainda usa DFT direta.
- Não foi atingida nem comprovada a meta de zero alocações em `queueInput`.
- Não foram medidos CPU, underruns, compatibilidade float em vários aparelhos ou estabilidade prolongada.

## Arquivos modificados/criados

- `app/src/main/java/com/batmusic/app/audio/BatAudioProcessor.kt`
- `app/src/main/java/com/batmusic/app/audio/LookaheadTruePeakLimiter.kt`
- `app/src/main/java/com/batmusic/app/audio/AudioEngine.kt`
- `app/src/main/java/com/batmusic/app/audio/AudioPresetRepository.kt`
- `app/src/main/java/com/batmusic/app/audio/BatAudioSettingsMigration.kt` (novo)
- `app/src/main/java/com/batmusic/app/PlaybackService.kt`
- `app/src/main/java/com/batmusic/app/MainActivity.kt`
- `app/src/main/java/com/batmusic/app/ui/screens/BatMusicScreens.kt`
- `app/src/test/java/com/batmusic/app/audio/BatAudioBaselineHarness.kt`
- `CLAUDE.md`
- `PROMPT_CLAUDE_CODE.md`
- `docs/BATBOOST_AUDITORIA.md` (novo)
- `docs/BATBOOST_RESULTADOS.md` (novo)
