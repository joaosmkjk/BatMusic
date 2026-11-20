# BatMusic — regras do projeto (BATBOOST X 3.0)

Coloque este arquivo na raiz do projeto (`~/Downloads/BatMusic/CLAUDE.md`).

## Contexto

- Estado atual: BATBOOST X 3.0 em desenvolvimento. Não declarar 3.0 pronto até todos os portões e a validação em aparelho passarem.

- App Android de player de música: Kotlin, Jetpack Compose (Material 3), Media3 / ExoPlayer.
- Pacote: `com.batmusic.app`. Serviço de reprodução: `PlaybackService.kt` (`MediaSessionService`).
- Motor de áudio próprio: BATBOOST X, um `BaseAudioProcessor` (`BatAudioProcessor`) ligado ao `DefaultAudioSink.Builder` pelo `RenderersFactory`.
- Missão: transformar o BATBOOST X em um motor de DSP de verdade (BATGAIN, BAT EQ, BATDYNAMICS, BATLIMITER, BAT ANALYZER), não em um simples volume booster.
- O relatório de auditoria (feito sobre o bytecode do APK, sem o código-fonte) está em `docs/BATBOOST_AUDITORIA.md`. Ele tem hipóteses marcadas que VOCÊ deve confirmar ou refutar no código real.

## Regra de ouro: evoluir, não refazer

Preservar sem alterar o comportamento:

- Integração Media3: `RenderersFactory` → `DefaultAudioSink.Builder` → `BatAudioProcessor`.
- Ciclo de vida do `PlaybackService`, fila e restauração de fila. Não tocar.
- Persistência em `SharedPreferences` (nome `batmusic`), `coerceIn` na leitura e migrações existentes (`preamp_db` → `preamp_db_float`).
- `AudioEngine` como fachada, `BatAudioSettings` como snapshot imutável publicado por campo `volatile`.
- Canal de comandos `PlaybackEffects` / `SET_AUDIO_EFFECTS` e o bypass A/B (`audioABOriginal`).
- A UI atual continua funcionando em todos os commits. Controles novos só entram depois do estágio correspondente passar nos testes.

Compatibilidade de dados:

- Campos novos em `BatAudioSettings` têm valores padrão IGUAIS ao comportamento atual (Q = 1, ceiling = 0,985, knee = 6 dB, release do limiter = release do compressor, etc.).
- O que o usuário já salvou (settings e presets) deve continuar valendo. Presets antigos são convertidos para o esquema novo na primeira leitura, com versão no esquema.

## Requisitos de engenharia (valem sempre)

- Compressor desligado por padrão; só ligar quando o usuário escolher ou um perfil validado o solicitar. O BATGAIN deve ser testável sem compressão interferindo.
- Release do BATLIMITER é independente do release do compressor e deve ser persistido/versionado.
- Publicar redução de ganho real do compressor e do limiter em dB; não exibir indicadores decorativos sem fonte no DSP.

- Zero alocações em `queueInput` após o aquecimento. Nada de boxing, `Pair`, `List<Float>`, lambdas ou iteradores no caminho de áudio.
- Coeficientes e planos de DSP são calculados FORA da thread de áudio e trocados por referência (`volatile`).
- Parâmetros mudam com rampa (10–20 ms) para evitar zipper noise. Bypass e A/B com crossfade curto.
- Não bloquear a thread de reprodução. Sem I/O, sem locks, sem logs no caminho de áudio.
- Preservar mono e estéreo, e várias taxas de amostragem (22,05 / 44,1 / 48 / 96 kHz). Frequências limitadas a 0,45 × fs.
- Com lookahead, implementar `onQueueEndOfStream` para esvaziar a cauda e tratar `onFlush` / troca de formato (gapless).
- Processamento desligado = cópia direta e bit-exata.
- Sem bibliotecas externas sem justificativa técnica escrita no commit e no relatório.
- Nunca inventar classes, APIs ou resultados de teste. Se algo não puder ser medido neste ambiente (som real, CPU em aparelho), diga isso explicitamente.

## Testes

- `docs/BATBOOST_AUDITORIA.md` é a auditoria do código-fonte atual; `docs/BATBOOST_VALIDATION.md` contém diagnóstico anterior e deve ser tratado como histórico quando divergir do código entregue.

- Testes JVM com um harness de renderização offline: alimenta `ByteBuffer`s no processador e mede a saída. Pasta: `app/src/test/java/com/batmusic/app/audio/`.
- Referência independente em `double` (RBJ para EQ, true peak com oversampling 8×) dentro dos testes.
- "Compila" não é aprovação. Cada fase só termina quando seus critérios numéricos passam.

## Critérios numéricos (propostos; calibrar depois do baseline)

- EQ: erro de resposta ≤ 0,1 dB vs referência em 31 frequências, em 44,1 / 48 / 96 kHz.
- DSP ligado e neutro: erro ≤ 1 LSB (16-bit). Bypass: bit-exato.
- Mudança de parâmetro: salto máximo entre amostras ≤ 1,2× o do sinal estacionário.
- Compressor: curva estática com erro ≤ 0,5 dB em 5 pontos; constantes de tempo ±10 %.
- Limiter: true peak de saída ≤ ceiling + 0,1 dB (referência 8×) em seno 0 dBFS, ISP fs/4 com fase 45°, onda quadrada clipada e material masterizado alto; 0 amostras acima de ±1; THD+N com 6 dB de redução de ganho ≤ −60 dB e melhor que o baseline; latência medida = lookahead declarado ± 1 amostra; última amostra preservada no fim do stream.
- Analyzer: RMS ± 0,1 dB; pico exato; true peak ± 0,2 dB; redução de ganho ± 0,1 dB.
- Segurança de ganho: o teto de referência de +24 dB existe, mas o ganho real é limitado por um orçamento de redução de ganho (mediana ≤ 3 dB, pico ≤ 6 dB; calibrar). Não prometer ganho ilimitado nem volume seguro em qualquer fone.

## Fluxo de trabalho

- Branch `feature/batboost-x-2` a partir da tag `baseline-batboost`.
- Um commit (ou mais) por fase, com mensagem clara e os números dos testes no corpo.
- Falhou um critério: pare, corrija; se não der, documente e pare. Não avance de fase com teste vermelho.
- Ao final, escreva `docs/BATBOOST_RESULTADOS.md` com o que foi feito, os números reais dos testes, o que NÃO foi possível verificar e os riscos restantes.
