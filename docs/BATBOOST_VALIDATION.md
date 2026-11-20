# BATBOOST X 2.0 — Auditoria cirúrgica da Etapa 0.5

Data: 2026-11-19  
Escopo: diagnóstico de overshoot e THD+N do `BatAudioProcessor` atual. Nenhum código de produção ou configuração de áudio foi alterado.

## Sumário do diagnóstico

1. **Overshoot entre amostras:** causa provável com confiança **alta**. O limiter mede e limita picos amostrais; não mede a forma reconstruída entre amostras. No sinal fs/4 com fase 45°, cada amostra limitada a 0,985 corresponde a uma senoide reconstruída com pico `sqrt(2) × 0,985 ≈ 1,393`. O resultado observado, 1,393010, coincide com essa previsão analítica dentro da resolução do interpolador usado.
2. **THD+N:** o valor inicialmente reportado, −55,829024 dB, foi obtido por um estimador que não usava janela coerente e não resolvia o ajuste seno/cosseno por mínimos quadrados. Controles com ganho linear e limiter inativo davam −61,922516 dB pelo método antigo, evidência de um piso de medição falso. Com uma janela coerente de 470 ciclos, os controles dão cerca de −154,7 dB e o caso com limiter dá **−57,053177 dB**. O valor corrigido permanece acima (pior que) a meta de −60 dB; a distorção desse caso vem predominantemente do limiter, com confiança **alta**.

## 1. Overshoot entre amostras

### Evidência no código

Em [`BatAudioProcessor.kt`](../app/src/main/java/com/batmusic/app/audio/BatAudioProcessor.kt):

- A entrada ao bloco DSP suporta PCM float e PCM16; o detector calcula `framePeak` amostral por frame e canal (linhas 145–158).
- O ganho BATBOOST é incluído em `makeupGain`; 200% representa aproximadamente +6,0206 dB, ou ganho linear 2 (linhas 126–138).
- O teto é `peakCeiling = 0.985f` (linha 141).
- O limiter deriva `targetLimiterGain` de `processedPeak`, atualiza o ganho amostra a amostra com ataque instantâneo e release lento, e aplica uma salvaguarda somente quando o **pico amostral** ultrapassa o teto (linhas 173–190).
- Não há detector true peak, oversampling no caminho de detecção nem lookahead. `coerceIn(-1f, 1f)` protege o limite numérico de cada amostra, mas não a reconstrução entre amostras.

### Relação matemática para o sinal fs/4, fase 45°

O sinal de entrada é:

```text
x[n] = sin(2π · (fs/4) · n/fs + π/4)
```

Os valores amostrados alternam entre `+√0,5` e `−√0,5`; portanto o pico de amostra da entrada é aproximadamente 0,707107. Com BATBOOST 200%, o pico antes do limiter é aproximadamente `2 × 0,707107 = 1,414214`. O limiter reduz os valores das amostras para o teto 0,985. A reconstrução ideal da sequência `±0,985` nessa fase fs/4 tem pico:

```text
0,985 × √2 = 1,393000 (aprox.)
```

O harness mediu 1,393010 com seu sinc 8×. O pequeno delta é compatível com a aproximação finita do filtro de interpolação. O alinhamento entre a previsão e o resultado, mais o código que só limita `processedPeak`, tornam a hipótese de causa altamente confiável.

A onda quadrada também evidencia a diferença entre pico de amostra e pico reconstruído: saída amostral 0,985000, true peak estimado 8× de 1,260911. O seno de 1 kHz dá 0,985350, muito próximo ao teto. O comportamento depende do conteúdo espectral e da fase, portanto o caso de 1 kHz não valida o limiter para inter-sample peaks.

### Correção recomendada

Substituir a detecção puramente amostral por detecção true peak com oversampling/interpolação apropriada e lookahead. O ganho de redução deve ser calculado antes de emitir as amostras que antecedem o pico, mantendo canais linkados e garantindo drenagem/flush da cauda. O teto amostral continua limitado a ±1 como proteção final, mas não substitui o teto true peak.

### Teste necessário e aprovação objetiva

Usar uma referência true peak independente do código do limiter e verificar, no mínimo, seno 0 dBFS, seno fs/4 fase 45°, onda quadrada clipada e varredura de fases/frequências próxima a Nyquist. Executar em 44,1, 48 e 96 kHz, mono/estéreo, float e PCM16 quando esse formato estiver no caminho testado.

Critérios objetivos:

- true peak de saída medido pela referência 8×: `≤ ceiling + 0,1 dB` em todos os sinais;
- zero amostras de saída acima de `|1|`;
- comparação do pico amostral e true peak contra o teto declarado, sem tratar um como substituto do outro;
- teste de flush/fim de stream preservando a cauda e a última amostra quando houver lookahead.

## 2. Decomposição da distorção

### Configuração do valor baseline

O sinal que gerou a leitura inicial de THD+N usa `BatAudioSettings(enabled=true, batBoost=200, compressorEnabled=false)`. EQ e bass boost permanecem desativados, o pré-ganho é zero e não há makeup do compressor. Logo:

- **EQ:** contribuição zero nesse ensaio, pois o EQ não estava ligado e os ganhos de banda não entram no caminho. Isso não estima a distorção do EQ em outras configurações.
- **Ganho:** o ganho de entrada/headroom é unitário para essa configuração. BATBOOST fornece o ganho linear aproximadamente constante 2,0. Ganho constante, isoladamente, não cria harmônicos.
- **Compressor:** desativado; não contribui com redução de ganho nesse ensaio.
- **Limiter:** é ativado pelo sinal pós-BATBOOST e muda o ganho em função do pico amostral. É o estágio não linear presente no ensaio.

### Controles focados

Foi feita uma repetição focada, justificada pela necessidade de separar ganho de limiter. O harness recompilou os fontes atuais; o método THD antigo e o corrigido foram aplicados aos mesmos controles:

| Caso a 48 kHz, 1 kHz, EQ/compressor desligados | Pico de entrada → saída | THD+N coerente | THD+N pelo método antigo |
|---|---:|---:|---:|
| Ganho unitário, seno amplitude 0,5, limiter inativo | 0,500 → 0,500 | −154,853673 dB | −61,922516 dB |
| BATBOOST 200%, seno amplitude 0,49, pós-ganho 0,98 abaixo do teto | 0,490 → 0,980 | −154,538601 dB | −61,922516 dB |
| Baseline: seno 0 dBFS + BATBOOST 200%, limiter ativo | 1,000 → 0,985 | **−57,053177 dB** | −55,829024 dB |

Os dois controles sem ação do limiter ficam cerca de 92 dB abaixo do resultado corrigido do caso baseline. Isso mostra que o ganho constante não explica a distorção medida; o limiter explica a maior parte dela. O EQ não participa porque está desligado. A atribuição ao limiter tem confiança alta para este sinal e configuração, mas não é uma caracterização completa de todos os sinais ou ajustes.

No baseline, `limiterGain` ataca instantaneamente quando o target cai e recupera com o release configurado para 120 ms. A decisão é refeita para cada frame, a partir do próprio sinal. Essa modulação de ganho correlacionada à forma de onda, junto com a salvaguarda instantânea, torna a relação entrada/saída não linear e gera componentes fora da fundamental. A amostra de saída não precisa exceder ±1 para haver distorção.

## 3. Adequação do método THD+N

### Método anterior e defeito encontrado

O harness inicial descartava 2.048 amostras e projetava o restante diretamente em seno/cosseno, assumindo ortogonalidade após remover a média. Para 1 kHz a 48 kHz, o período é 48 amostras. O registro restante tinha `24.576 − 2.048 = 22.528` amostras, isto é, 469⅓ ciclos, não um número inteiro de ciclos. Nesse caso as bases seno, cosseno e DC não são exatamente ortogonais; projetar como se fossem ortogonais deixa parte da fundamental no resíduo.

A evidência é o piso idêntico de −61,922516 dB nos dois controles sem distorção não linear. Por isso o valor inicial de −55,829024 dB não deve ser usado como THD+N de referência.

### Método corrigido usado nesta validação

O registro começa em 2.016 amostras, que correspondem a 42 ciclos, e usa as 22.560 amostras restantes, exatamente 470 ciclos a 1 kHz/48 kHz. Com essa janela coerente, a projeção síncrona após remover DC equivale ao ajuste least-squares seno/cosseno para esta frequência. Os controles lineares dão −154,853673 e −154,538601 dB; o caso com limiter dá −57,053177 dB.

Para outras frequências/taxas que não admitam janela coerente, o teste deve resolver explicitamente mínimos quadrados para `[DC, seno(f), cosseno(f)]`, ou usar um método espectral com janela e correção de ganho apropriada. Não se deve comparar o valor antigo com o corrigido como se fossem o mesmo estimador.

### Critério objetivo de THD+N

Com um estimador coerente/least-squares independente e sinal de entrada fixado:

- controle linear abaixo do limiter deve ficar próximo ao piso numérico (recomendação: `≤ −120 dB` no harness float atual);
- caso de limiter com redução de pico declarada em aproximadamente 6 dB deve atingir `THD+N ≤ −60 dB` e melhorar sobre o baseline corrigido de **−57,053177 dB**;
- informar frequência, janela/ciclos, taxa, amplitude, medição de ganho e faixa excluída de transiente junto ao resultado.

## 4. Limitações do interpolador sinc 8×

O código de teste usa 8 fases por amostra e uma convolução de 64 taps com sinc ideal janelada por Blackman, normalizando a soma dos pesos. É um estimador offline útil e reproduziu o resultado analítico do seno fs/4, mas não deve ser descrito como medidor normativo certificado.

Limitações específicas:

- oito fases observam a reconstrução a cada 1/8 de amostra; um máximo contínuo entre fases pode ficar ligeiramente acima do máximo observado;
- 64 taps e janela Blackman aproximam, mas não implementam uma sinc infinita; comprimento e janela afetam atenuação, ripple e estimativa perto de Nyquist;
- o resultado pressupõe reconstrução band-limited ideal dos samples digitais; uma onda quadrada analógica contém harmônicos fora da banda e não é recuperável unicamente dos samples;
- o harness descarta 32 amostras nas bordas para evitar transientes da convolução, então não mede picos de início/fim de streams curtos;
- normalizar os pesos reduz erro de ganho DC, mas não prova calibração de pico para toda frequência/fase;
- o cálculo não foi comparado contra uma implementação independente normativa/validada. A estimativa do quadrado, em particular, tem confiança menor que o caso fs/4 analiticamente conhecido.

Para aprovação final, comparar a implementação com referência true peak independente e validar frequência/fase. O resultado fs/4 tem suporte analítico independente; os valores numéricos da onda quadrada permanecem estimativas do harness.

## Correção e teste recomendados

1. Implementar true-peak limiting com lookahead após autorização própria, sem alterar a integração do sink fora da mudança aprovada.
2. Preservar o harness e o método coerente/least-squares; manter teste de controle linear para detectar leakage do estimador.
3. Medir true peak com duas implementações independentes, incluindo a sequência fs/4 fase 45° e a onda quadrada, em várias fases e taxas.
4. Aprovar somente quando `TP ≤ teto + 0,1 dB`, nenhuma amostra superar ±1, o THD+N do caso com cerca de 6 dB de redução for `≤ −60 dB` e melhor que −57,053177 dB, e os controles lineares permanecerem abaixo de −120 dB.

Este documento é diagnóstico, não autorização de implementação. A Etapa 1 continua bloqueada aguardando aprovação.
