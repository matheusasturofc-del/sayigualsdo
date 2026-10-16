# Olhar Fatal 👁️

Mod **Fabric** para **Minecraft 1.21.1** com dois times:

| Time | O que acontece |
|---|---|
| **Kill** (vermelho) | Mata quem é do time **View** com **um hit**. Mas, enquanto alguém do View estiver olhando para ele, fica **congelado**, **contornado de vermelho** e soltando **partículas vermelhas**. Quando ninguém olha, volta a se mexer. |
| **View** (azul) | Morre com um hit do Kill. Para se proteger, tem que ficar olhando para ele — no **rosto** ou em **qualquer parte do corpo**. |

Detalhes:

- Conta como "olhando" se a mira estiver em cima dele **ou** se alguma parte do corpo estiver perto do meio da tela
  (35° a partir da mira, dá para mudar com `/equipe angulo`). Até 64 blocos de distância.
- Paredes bloqueiam o olhar. Vidro, folhas, grades e outros blocos transparentes **não** bloqueiam.
- Quem está com **cegueira** não congela ninguém.
- Congelado, o Kill não anda, não pula, não cai e também **não pode bater, quebrar blocos nem usar itens**
  (só consegue mexer a câmera). Se for empurrado, volta para o mesmo lugar.
- O hit do Kill mata mesmo com armadura, escudo, totem ou resistência. Jogadores em criativo/espectador não morrem.
- Os times são os times normais do Minecraft (`View` e `Kill`), então ficam salvos no mundo e também funcionam com
  `/team join Kill <jogador>`.

## Instalação

1. Instale o [Fabric Loader](https://fabricmc.net/use/) para o Minecraft **1.21.1**.
2. Coloque na pasta `mods`:
   - [Fabric API](https://modrinth.com/mod/fabric-api) (1.21.1)
   - `olharfatal-1.0.0+1.21.1.jar` (este mod) — baixe na página **Releases** do GitHub, em *Olhar Fatal (download)*
     (use o arquivo **sem** `-sources`).

> O mod só precisa estar em **quem hospeda** o mundo (servidor ou quem abriu o mundo para LAN).
> Quem só entra no mundo não precisa instalar nada.

## Comandos

| Comando | O que faz |
|---|---|
| `/equipe view` | Você entra no time **View** |
| `/equipe kill` | Você entra no time **Kill** |
| `/equipe sair` | Você sai dos times |
| `/equipe view <jogadores>` / `kill <jogadores>` / `sair <jogadores>` | Coloca/tira outros jogadores (precisa ser OP) |
| `/equipe lista` | Mostra quem está em cada time |
| `/equipe angulo <graus>` | Quão perto do meio da tela ele precisa estar para congelar (OP, padrão 35) |
| `/equipe distancia <blocos>` | Distância máxima do olhar (OP, padrão 64) |
| `/equipe ligar` / `desligar` | Liga ou desliga o mod (OP) |
| `/equipe recarregar` | Recarrega `config/olharfatal.json` (OP) |

Exemplo: você digita `/equipe view` e seu amigo digita `/equipe kill`.

## Configuração (`config/olharfatal.json`)

| Opção | Padrão | Descrição |
|---|---|---|
| `enabled` | `true` | Liga/desliga o mod |
| `viewAngle` | `35` | Ângulo (graus a partir da mira) em que uma parte do corpo conta como "olhando" |
| `maxDistance` | `64` | Distância máxima do olhar |
| `seeThroughGlass` | `true` | Olhar através de vidro, folhas, grades... também congela |
| `redOutline` | `true` | Contorno vermelho no Kill congelado |
| `redParticles` | `true` | Partículas vermelhas no Kill congelado |
| `particlesPerTick` | `3` | Quantidade de partículas |
| `blockActionsWhileFrozen` | `true` | Congelado não bate, não quebra e não usa itens |
| `oneHitKill` | `true` | Kill mata View com um hit |
| `blindnessStopsGaze` | `true` | Com cegueira o View não congela ninguém |

## Compilar

```
./gradlew -p olharfatal build
```

O jar sai em `olharfatal/build/libs/`.
