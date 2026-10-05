# T1 – Sistemas Operacionais (T1-A + T1-B + T1-C)

## Compilar e executar

```
javac Sistema.java
java Sistema [tamMem] [tamPg] [delta]      # padrão: 1024 8 10
```

- `tamMem` deve ser múltiplo de `tamPg` (nº de frames = tamMem / tamPg)
- `delta` = nº de instruções por fatia de tempo (relógio)

## Organização

| Parte | Onde no código |
|-------|----------------|
| T1-A Gerente de Memória | classe `GM` (`aloca` / `desaloca`), `Utilities.carga`, tradução na CPU (`CPU.traduz`) |
| T1-B Gerente de Processos | classes `PCB` e `GP` (`criaProcesso` / `desalocaProcesso`), `running` e `ready` no `SO`, comandos do shell |
| T1-C Escalonamento | interrupção `intTimer` a cada `delta` instruções, `SO.rotTimer`, `SO.finalizaRunning`, threads `Escalonador` e `CPU` com `semaSch` / `semaCPU` |

Threads: **Shell** (main), **Escalonador** e **CPU**, conforme o esquema multithreaded da aula.

## Comandos

`new <prog>`, `rm <id>`, `ps`, `dump <id>`, `dumpM <ini> <fim>`, `exec <id>`, `execAll`,
`traceOn`/`traceOff`, `exit`; extras: `autoOn`/`autoOff` (escalonamento contínuo),
`frames`, `logOn`/`logOff`, `delay <ms>`, `help`.

## Roteiro de demonstração

```
new fatorial
new fatorialV2
new fibonacci10
ps
frames
dump 2
execAll            # round-robin até todos terminarem
dumpM 0 16         # resultados permanecem na memória física
autoOn             # escalonamento contínuo (T1-C 3.2)
delay 20
new fibonacci10
new fatorial
ps                 # mostra processos rodando enquanto o shell atende
```

## Observação sobre os programas PB e PC

Os programas não foram alterados. Porém **PB** acessa o endereço 50 tendo só 16 palavras,
e **PC** acessa os endereços 96–99 tendo só 54 palavras. Com proteção de memória por página,
esses acessos geram `intEnderecoInvalido` e o processo é finalizado, a menos que a página seja
grande o suficiente para cobrir esses endereços (ex.: `java Sistema 1024 128 10` roda o PC
até o fim e ordena o vetor).
