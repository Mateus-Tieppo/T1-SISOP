# Sistemas Operacionais – T1 + T2a (IO concorrente) + T2b (Memória Virtual)

## Compilar e executar

```
javac Sistema.java
java Sistema [tamMem] [tamPg] [delta]      # padrão: 1024 8 10
```

- `tamMem` deve ser múltiplo de `tamPg` (nº de frames = tamMem / tamPg)
- `delta` = nº de instruções por fatia de tempo (relógio)
- área de swap do disco = max(64, 4 × nº de frames) slots de `tamPg` palavras

## Threads (esquema multithreaded da aula)

| Thread | Papel |
|--------|-------|
| **Shell** (main) | lê comandos e submete ao SO; números digitados são repassados ao Console (resposta de IN) |
| **Escalonador** | aguarda em `semaSch`; escolhe processo da fila de prontos, restaura contexto e libera a CPU (`semaCPU`) |
| **CPU** | aguarda em `semaCPU`; trata interrupções de dispositivos; executa instruções até timer, syscall, page fault, STOP ou erro |
| **Console** | consome a *Fila Pedidos Console* (`<pid, IN/OUT, end>`), faz DMA na memória do processo e gera `intIO` |
| **IO-VM** (Disco) | consome a *Fila Pedidos IO-VM*: carrega página (do programa ou do swap) num frame, ou salva frame vitimado no swap; gera `intPaginaCarregada` / `intPaginaSalva` |

## T2a – Projeto concorrente

- Processos com 3 estados: **rodando**, **pronto**, **bloqueado** (filas `ready`, `bloqueadosConsole`, `bloqueadosVM`).
- `SYSCALL` (IN/OUT) → `SO.chamadaIO`: salva contexto no PCB, bloqueia o processo, coloca o pedido na fila
  do Console (produtor/consumidor) e libera o escalonador, que põe outro processo na CPU.
- O Console é concorrente com a CPU. Ao terminar, escreve a interrupção na CPU (`CPU.interrompe`).
  A rotina `SO.rotRetIO` passa o processo de bloqueado para pronto, e o processo interrompido continua sua fatia.
- DMA: o Console lê/escreve diretamente a posição física. O frame fica **fixo** (não pode ser vitimado) enquanto o pedido está pendente.
- Se a CPU estiver ociosa (todos bloqueados), a interrupção do dispositivo a acorda para tratar o evento.
- O sistema opera continuamente (escalonamento contínuo ligado por padrão), e o shell cria processos a qualquer momento.

## T2b – Memória virtual

- **Criação**: o processo recebe tabela de páginas com todas as páginas, mas só a **página 0** é carregada num frame.
- **Tabela de páginas** (`EntradaTP`): cada página está em um destes estados:
  - `NAO_CARREGADA`: nunca foi carregada, o conteúdo é a página do programa original no disco
  - `MEMORIA`: está no frame `frame`
  - `DISCO`: foi vitimada, e a cópia está no slot `slot` do swap
  - `EM_CARGA`: page fault em tratamento, frame reservado aguardando o disco
- **Page fault** (`CPU.traduz` → `intPageFault` → `SO.rotPageFault`): o processo é bloqueado (o pc não avança e a instrução é
  reexecutada depois), pede frame ao GM e escalona outro processo.
  - frame livre → [**] mapeia página/frame e pede ao disco a carga da página
  - sem frame livre → **vitima** uma página (FIFO), marca-a como `DISCO` na tabela do dono e pede ao disco que a salve.
    A interrupção `intPaginaSalva` entrega o frame ao processo demandante e continua em [**].
  - `intPaginaCarregada` → página fica `MEMORIA`, e o processo passa de bloqueado para pronto (fim do page fault)
- **Política de vitimação** (`GM.escolheVitima`): FIFO por ordem de carga. Não são vitimados frames em trânsito com o disco,
  frames com DMA de console pendente nem páginas recém-carregadas cujo dono ainda não completou uma instrução. Sem esta
  última proteção, com muitos processos e poucos frames, a FIFO pura entra em thrashing sem progresso. Em impasse total
  (nada pronto ou rodando e nada em trânsito), aceita a página nova de outro processo bloqueado.
- **Disco** (`Disco` + gerente de swap `GD`): contém os programas e a área de swap. O `GD` aloca e libera slots como o GM faz com
  frames. A fila do disco é FIFO, então a carga de uma página é sempre atendida depois do seu salvamento.
- Ao terminar um processo, seus frames e slots de swap são liberados. Frames em trânsito são liberados quando o disco responde.

## Comandos

`new <prog>`, `rm <id>`, `ps`, `dump <id>`, `dumpM <ini> <fim>`, `frames`, `swap`, `<inteiro>` (resposta a IN),
`exec <id>`, `execAll`, `autoOn`/`autoOff`, `traceOn`/`traceOff`, `logOn`/`logOff`,
`delay <ms>` (CPU), `delayIO <ms>` (console), `delayDisco <ms>` (disco, padrão 20), `help`, `exit`.

- `ps`: estado de cada processo (com motivo do bloqueio: IO ou VM), page faults e **frames usados**
  (`p0:f3` = página 0 no frame 3, `p2:swap5` = página 2 no swap slot 5, `p1:-` = nunca carregada).
- `frames`: dono e página de cada frame (`*` em trânsito com o disco, `!` fixo por IO), além da ordem FIFO.
- `dump <id>`: tabela de páginas e conteúdo lógico do processo, venha ele da memória, do swap ou do programa.
- Com `logOn` (padrão), cada page fault, vitimação, carga e IO é mostrado com os frames do processo, o que permite
  acompanhar a mudança dos frames durante a execução.

## Roteiro de demonstração

T2a: IO concorrente (memória grande, sem vitimação):

```
java Sistema
new fibonacciREAD      # pede IN e fica bloqueado(IO)
new fatorialV2         # roda enquanto o outro espera; faz OUT
new fibonacci10
ps                     # estados rodando / pronto / bloqueado(IO)
7                      # responde o IN do fibonacciREAD
```

T2b: memória virtual com vitimação (6 frames):

```
java Sistema 48 8 5
new fibonacci10
new fatorialV2
new fatorial
new fibonacci10v2
ps                     # frames de cada processo mudando (p2:f4, p0:swap1, ...)
frames                 # quem ocupa cada frame, ordem FIFO
swap                   # paginas salvas no disco
dump 1                 # memoria logica: parte em frames, parte no swap
```

## Observação sobre os programas PB e PC

Os programas não foram alterados. Porém **PB** acessa o endereço 50 tendo só 16 palavras,
e **PC** acessa os endereços 96–99 tendo só 54 palavras. Com proteção de memória por página,
esses acessos geram `intEnderecoInvalido` e o processo é finalizado, a menos que a página seja
grande o suficiente para cobrir esses endereços (ex.: `java Sistema 1024 128 10` roda o PC
até o fim e ordena o vetor).
