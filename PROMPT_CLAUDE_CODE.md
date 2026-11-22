# Prompt mestre — BATBOOST X 3.0 (todas as fases em uma execução)

Este prompt evolui a branch de trabalho já existente. Preserve o baseline/tag e a branch atuais; não recrie tags, não troque de branch e não sobrescreva alterações locais. Use `CLAUDE.md`, `docs/BATBOOST_AUDITORIA.md`, `docs/BATBOOST_BASELINE.md` e `docs/BATBOOST_VALIDATION.md`, distinguindo código atual de relatórios históricos.

---

Você vai executar a evolução completa do BATBOOST X 3.0 (fases 1 a 4) neste projeto, em uma única execução, seguindo o `CLAUDE.md` e o relatório `docs/BATBOOST_AUDITORIA.md`. A estrutura atual deve ser EVOLUÍDA por dentro, não refeita. Trabalhe por portões: cada etapa só começa quando a anterior passou nos critérios. Se um portão falhar e você não conseguir corrigir, pare e reporte.

## Etapa 0 — Verificação e baseline (não altere código de produção)

1. Mapeie o código real: quem chama `BatAudioProcessor`, onde `BatAudioSettings` e `SavedAudioPreset` são construídos, como o `RenderersFactory` monta o sink, como o app usa velocidade de reprodução e skip-silence, e se há offload de áudio.
2. O relatório foi feito só com o bytecode do APK. Confirme ou refute cada ponto marcado como hipótese (P4 zipper noise, P6 custo e alocações, P8 biquad float32, P10 ordem frente ao Sonic) e liste qualquer divergência entre o relatório e o código real.
3. Confirme a versão do Media3 no `build.gradle` e as APIs de `BaseAudioProcessor` disponíveis nela (`onQueueEndOfStream`, `onFlush`, `replaceOutputBuffer`).
4. Crie o harness de teste offline (JVM) e MEÇA o baseline do código atual: resposta do EQ, THD+N do limiter com 6 dB de redução, true peak em seno 0 dBFS / ISP fs/4 fase 45° / quadrada clipada, latência, e alocações por bloco. Salve os números em `docs/BATBOOST_BASELINE.md`.
5. Ajuste os critérios numéricos do `CLAUDE.md` com base no baseline, sem relaxá-los sem justificativa. Commit.

Portão: baseline medido e documentado; divergências do relatório listadas.

## Etapa 1 — Fundação: BATGAIN e BAT EQ

- Transforme `BatAudioProcessor` em host de um pipeline interno de estágios (mesmo nome de classe e mesma integração Media3).
- Plano de DSP calculado fora da thread de áudio e trocado por referência; zero alocações em `queueInput`; sem `Pair` nem boxing.
- BATGAIN: ganho de entrada, pré-ganho e saída em dB; compressor desligado por padrão e BATGAIN medido isoladamente; headroom gerenciado por medição (substitui a heurística 7 dB / 0,35 / 0,25), mantendo o teto de referência de +24 dB e o comportamento padrão atual por padrão.
- BAT EQ: tipos Peak, Low Shelf e High Shelf; frequência, ganho e Q configuráveis; ativação independente; rampas de parâmetro. Avalie TDF2 ou double conforme um teste de varredura em 31 Hz.
- Estenda `BatAudioSettings` com valores padrão iguais ao comportamento atual; versione o esquema e converta presets antigos.
- Saída: arredondamento + dither em vez de truncamento (quando o DSP estiver ligado).

Portão: EQ ≤ 0,1 dB vs referência; neutro ≤ 1 LSB; bypass bit-exato; 0 alocações em `queueInput`; salto entre amostras ≤ 1,2×. Commit.

## Etapa 2 — Dinâmica: BATDYNAMICS e BATLIMITER

- Compressor: gain computer em domínio log, knee configurável (padrão 6 dB), threshold, ratio, attack, release e makeup; detector peak ou RMS; medição de redução de ganho.
- Limiter novo: release próprio, independente do compressor; lookahead (padrão 3 ms, faixa 1–5 ms), ceiling configurável (padrão atual 0,985 preservado como opção; recomendado −1 dBTP), release próprio (20–1000 ms) independente do compressor, true peak por oversampling na detecção (4× em 44,1/48 kHz, 2× em 96 kHz) e medição de ganho reduzido.
- Implemente `onQueueEndOfStream` para esvaziar a cauda e trate `onFlush` e troca de formato.
- Mantenha o limiter antigo atrás de uma chave interna de debug, para comparação A/B e rollback.
- Avalie, com base no código real, se o processador precisa ficar depois do Sonic (`setAudioProcessorChain`) e aja conforme o resultado da Etapa 0.

Portão: curva do compressor ≤ 0,5 dB; tempos ±10 %; true peak ≤ ceiling + 0,1 dB (referência 8×); 0 amostras acima de ±1; THD+N com 6 dB de GR ≤ −60 dB e melhor que o baseline; latência = lookahead declarado ± 1 amostra; última amostra preservada. Commit.

## Etapa 3 — Monitoramento: BAT ANALYZER

- Medições reais, pré e pós: RMS, pico, true peak estimado, headroom e redução de ganho (compressor e limiter), sempre com valores provenientes do DSP.
- A thread de áudio só escreve em buffers/atômicos lock-free; o espectro usa FFT fora da thread de áudio (substitui a DFT direta).
- Publique os dados novos pelo canal de status existente, mantendo as chaves atuais.

Portão: RMS ± 0,1 dB; pico exato; TP ± 0,2 dB; GR ± 0,1 dB; tom de 1 kHz no bin correto (± 1 bin, ± 1 dB). Commit.

## Etapa 4 — Refinamento: perfis, interface e otimização

- Perfis como dados (`BatProfile`): ROCK primeiro (ponto de partida: low shelf ≈ +2 dB em ≈ 100 Hz, corte ≈ −1,5 dB em ≈ 400 Hz com Q 1, presença ≈ +1,5 dB em ≈ 3 kHz com Q 1, high shelf ≈ +2 dB em ≈ 10 kHz, compressão 2:1 com attack ≈ 20 ms e release ≈ 150 ms, limiter −1 dBTP), depois METAL, ELECTRONIC, CLASSICAL, VOCAL e CUSTOM. Todos alteram parâmetros reais do pipeline.
- Auto-headroom: orçamento de redução de ganho (mediana ≤ 3 dB, pico ≤ 6 dB) limitando o makeup automático; use tags ReplayGain/R128 quando existirem.
- UI (`BatAudioScreen` / `BoostPanel`): controles novos (Q, ceiling, release, perfis) e medidores reais, sem quebrar as telas atuais.
- Otimização: tire do laço qualquer cálculo invariante, meça o custo por bloco no harness e compare com o baseline.

Portão: todos os testes anteriores continuam verdes; perfis alteram a saída de forma mensurável; UI compila e os controles antigos continuam funcionando. Commit.

## Entrega final

Atualize `docs/BATBOOST_AUDITORIA.md` quando novas divergências forem confirmadas. Escreva `docs/BATBOOST_RESULTADOS.md` com: o que foi feito por etapa, os números REAIS dos testes (baseline vs final), os arquivos modificados e criados, as divergências encontradas em relação ao relatório, e uma lista explícita do que NÃO foi possível verificar neste ambiente (escuta real em fone, CPU e underruns em aparelho, estabilidade de 8 h, compatibilidade com saída float em vários aparelhos). Não declare nada como aprovado sem número de teste que o sustente.
