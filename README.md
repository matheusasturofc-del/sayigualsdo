# SayIgual 🎤

Mod **Fabric** para **Minecraft 1.21.1** ligado ao **[Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat)**.
Toda vez que alguém fala no voice chat (em **português** ou **inglês**) o nome de:

- **um bloco** → todos os blocos das **64 chunks mais perto** de quem falou viram esse bloco.
  Ex.: *"bloco de diamante"* / *"diamond block"* → tudo vira bloco de diamante.
- **um mob** → nascem **20** dele em volta de quem falou.
  Ex.: *"creeper"*, *"zumbi"*, *"porco"*, *"cow"*.

O reconhecimento de voz é **offline** ([Vosk](https://alphacephei.com/vosk/)) e roda no servidor
(no singleplayer, roda no seu próprio jogo). Nada é enviado para a internet.

## Instalação

1. Instale o [Fabric Loader](https://fabricmc.net/use/) para o Minecraft **1.21.1**.
2. Coloque na pasta `mods`:
   - [Fabric API](https://modrinth.com/mod/fabric-api) (1.21.1)
   - [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) (Fabric 1.21.1)
   - `sayigualsdo-1.0.0+1.21.1.jar` (este mod)
3. Abra o jogo / servidor. **Na primeira vez** o mod baixa sozinho os modelos de voz (~80 MB, português e inglês)
   para a pasta `sayigualsdo/models`. Use `/sayigual status` para ver quando terminou
   (aparece `voz: ouvindo em pt, en`).

> Em servidor dedicado, o mod só precisa estar no **servidor** (os jogadores só precisam do Simple Voice Chat).
> Ele funciona em **Windows, Linux e macOS** (64 bits).

### Como pegar o .jar

O GitHub Actions compila o mod a cada push: aba **Actions** → último *build* → artefato **sayigualsdo**.
Use o arquivo **sem** `-sources` no nome. Ou compile você mesmo com `./gradlew build` (o jar sai em `build/libs/`).

## Como funciona

- O plugin do Simple Voice Chat recebe o áudio do microfone de cada jogador, decodifica o Opus
  e manda para dois reconhecedores ao mesmo tempo (português e inglês).
- O texto reconhecido é comparado com os nomes de **todos** os blocos e mobs do jogo nos dois idiomas
  (sem acento, sem diferenciar maiúsculas e aceitando plural: *"blocos de diamante"*, *"zumbis"*).
  Se aparecerem vários nomes, vale o mais comprido.
- Os blocos são trocados aos poucos (40 mil por tick) para não travar o servidor. Só blocos sólidos/líquidos
  são trocados — o ar continua ar (dá para mudar com `replaceAir`).
- Baús e outros containers são esvaziados antes de serem trocados (igual ao `/fill`), para não encher o chão de itens.
- Toca o **som de XP** quando termina de trocar os blocos ou de fazer os 20 mobs nascerem (desligue com `playXpSound`).
- Mobs hostis não aparecem no modo **Pacífico**.

## Comandos (precisa ser OP / ter cheats ligados)

| Comando | O que faz |
|---|---|
| `/sayigual falar <texto>` | Simula uma fala (ótimo para testar sem microfone). Ex.: `/sayigual falar bloco de diamante` |
| `/sayigual status` | Mostra se os modelos de voz carregaram |
| `/sayigual ligar` / `desligar` | Liga ou desliga o mod |
| `/sayigual recarregar` | Recarrega `config/sayigualsdo.json` |

## Configuração (`config/sayigualsdo.json`)

| Opção | Padrão | Descrição |
|---|---|---|
| `chunkCount` | `64` | Quantas chunks mais próximas são trocadas |
| `mobCount` | `20` | Quantos mobs nascem |
| `mobSpawnRadius` | `8` | Raio (blocos) onde os mobs nascem |
| `blocksPerTick` | `40000` | Blocos trocados por tick (menos = menos lag, mais devagar) |
| `cooldownSeconds` | `3.0` | Tempo mínimo entre dois efeitos do mesmo jogador |
| `replaceAir` | `false` | Se `true`, o ar também vira o bloco (você fica preso dentro!) |
| `showTranscripts` | `true` | Mostra na action bar o que o mod entendeu |
| `announceToEveryone` | `true` | Avisa no chat de todos |
| `playXpSound` | `true` | Som de XP quando termina de trocar os blocos / fazer os mobs nascerem |
| `useGrammar` | `false` | Faz o reconhecimento só procurar nomes de blocos/mobs (mais preciso, mas qualquer fala vira algum bloco) |
| `minNameLength` | `3` | Ignora nomes muito curtos |
| `blockBlacklist` / `entityBlacklist` | ar, pistão em movimento, luz... | Ids que nunca são usados (ex.: `"minecraft:ender_dragon"`) |
| `aliases` | `"diamante" → diamond_block`, ... | Apelidos extras: frase → id do bloco ou mob |
| `modelUrls` | modelos *small* do Vosk | Troque por modelos maiores para reconhecer melhor |
| `autoDownloadModels` | `true` | Baixa os modelos na primeira vez |

Se o download automático não funcionar, baixe e extraia manualmente em `<pasta do jogo>/sayigualsdo/models/`:

- <https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip>
- <https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip>

(ficando `sayigualsdo/models/vosk-model-small-pt-0.3/` e `sayigualsdo/models/vosk-model-small-en-us-0.15/`).

## Outro mod deste repositório: Olhar Fatal 👁️

Time **Kill** mata o time **View** com um hit, mas congela (contornado de vermelho, soltando partículas vermelhas)
enquanto alguém do View olha para ele. Veja [`olharfatal/README.md`](olharfatal/README.md).

## Licença

MIT
