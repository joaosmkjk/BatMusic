# BATBOOST X — auditoria e patch de segurança/telemetria

## Base e escopo

Patch preparado sobre `feature/batboost-x-2`, sem alterar a branch de trabalho original. O objetivo desta etapa é corrigir dois defeitos demonstráveis no código e tornar os medidores menos enganosos. Não é uma certificação de loudness nem uma validação auditiva.

## Defeitos encontrados e corrigidos

### 1. O limitador era bypassado pelo interruptor principal do BAT

Antes, `BatAudioProcessor.queueInput` passava `config.enabled` como sinal `active` para `LookaheadTruePeakLimiter.processFrame`. Como `enabled` é calculado a partir de `batAudioEnabled`, desligar o processamento BAT também marcava todos os frames como bypassados pelo limitador. Isso contradizia o texto da interface que dizia que o teto de proteção permanecia ativo.

Correção:
- `BatAudioSettings` agora separa `enabled` (EQ/ganho/processamento tonal) de `limiterEnabled`.
- O limitador permanece habilitado mesmo quando o usuário desliga o processamento BAT.
- O A/B Original continua sendo um bypass integral, incluindo o limitador, para preservar a comparação original.

### 2. O medidor de pico de saída escondia valores acima de 100%

Antes, o pico medido era truncado em 1.0 no processador e novamente limitado a 1.0 ao receber o valor na Activity. Isso poderia ocultar um overshoot inesperado no caminho PCM float.

Correção:
- O processador agora publica o pico de amostra medido sem truncar em 1.0.
- A Activity aceita valores até 8.0 para fins de diagnóstico.
- A barra visual continua limitada à faixa gráfica de 0–100%, mas o texto numérico mostra o valor real.
- Os rótulos agora dizem explicitamente “pico de amostra”; não devem ser confundidos com true peak.

### 3. Entrada PCM float não finita

Antes, NaN/Infinity podia entrar no detector, no acumulador RMS e no caminho de bypass float.

Correção:
- Valores float não finitos são convertidos em zero durante a leitura do bloco.
- O buffer bruto usado no bypass recebe a representação do valor já sanitizado.

## Limitações que continuam abertas

- O algoritmo de true peak 4x/16 taps ainda precisa ser comparado com um analisador de referência e sinais de teste independentes. O comentário do código não substitui essa validação.
- O medidor “Pico pré-limiter” mede pico de amostra do bloco, não true peak intersample.
- Ainda não há um medidor independente de true peak pós-limitador. O pico de saída atual é pico de amostra.
- O valor de GR por bloco representa a redução máxima do bloco, não uma média perceptual.
- O contador de blocos limitados indica blocos em que houve redução, não a quantidade de amostras clipadas.
- Não se deve aumentar ganho nem remover o teto de proteção com base apenas no RMS de saída.

## Plano obrigatório de validação local

1. Executar `./gradlew clean testDebugUnitTest assembleDebug`.
2. Confirmar no app que o limitador continua ativo quando o switch BAT está desligado.
3. Confirmar que o A/B Original realmente bypassa o processamento e o limitador.
4. Reproduzir áudio em PCM float e PCM 16-bit; verificar se o medidor de pico de saída não mascara leituras acima de 100%.
5. Testar silêncio, senoides em níveis conhecidos, impulso/transiente e material musical com picos densos.
6. Comparar true peak pré e pós-limitador com analisador externo confiável; confirmar teto de -1 dBFS true peak, considerando o headroom interno configurado.
7. Fazer escuta A/B em volume de reprodução igualado antes de qualquer ajuste de loudness.

## Resultado desta etapa

Correções de código aplicadas em uma branch isolada. Build, testes automatizados, análise independente de true peak e teste auditivo em dispositivo ainda precisam ser executados; portanto, este patch não deve ser anunciado como versão final validada.
