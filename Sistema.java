// PUCRS - Escola Politécnica - Sistemas Operacionais
// Prof. Fernando Dotti
// Código fornecido como parte da solução do projeto de Sistemas Operacionais
//
// Trabalho T1  = T1-A (gerente de memória paginada)
//              + T1-B (gerente de processos)
//              + T1-C (escalonamento round-robin contínuo, multithreaded)
// Trabalho T2a = T1 + IO concorrente: processos com 3 estados (running, ready, blocked),
//                dispositivo Console como thread separada com fila de pedidos
//                (produtor/consumidor), DMA, interrupção de fim de IO, shell concorrente.
// Trabalho T2b = T2a + Memória Virtual: só a primeira página é carregada na criação,
//                page fault, dispositivo Disco (thread IO-VM) com área de programas e
//                área de swap, vitimação FIFO de páginas, interrupções de fim de
//                salvamento e de fim de carga de página.
//
// Estrutura deste código:
//    Todo código está dentro da classe *Sistema*
//    Dentro de Sistema, encontra-se acima a definição de HW:
//           Memory,  Word,
//           CPU tem Opcodes (codigos de operacoes suportadas na cpu),
//               e Interrupcoes possíveis, define o que executa para cada instrucao.
//               A CPU traduz todo endereço lógico para físico usando a tabela de
//               páginas do processo (MMU). Se a página não está na memória, gera
//               page fault. A cada "delta" instruções gera interrupção de relógio.
//               Dispositivos sinalizam interrupções escrevendo numa fila da CPU.
//           Console (thread) e Disco (thread IO-VM) - dispositivos
//           VM -  a máquina virtual é uma instanciação de CPU e Memória
//    Depois as definições de SW (SO):
//           InterruptHandling, SysCallHandling,
//           GM  - gerente de memória (paginação + memória virtual)
//           GD  - gerente do espaço de swap no disco
//           PCB - descritor de processo, com tabela de páginas estendida
//           GP  - gerente de processos
//           Escalonador (thread) e CPU (thread)
//           Shell (thread main) - interpreta comandos do usuário
//    Por último os programas existentes (estão no "disco").
//
// Uso:  java Sistema [tamMem] [tamPg] [delta]
//       padrão: tamMem=1024, tamPg=8, delta=10

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

public class Sistema {

	// -------------------------------------------------------------------------------------------------------
	// --------------------- H A R D W A R E - definicoes de HW
	// ----------------------------------------------

	// -------------------------------------------------------------------------------------------------------
	// --------------------- M E M O R I A - definicoes de palavra de memoria,
	// memória ----------------------

	public class Memory {
		public Word[] pos; // pos[i] é a posição i da memória. cada posição é uma palavra.

		public Memory(int size) {
			pos = new Word[size];
			for (int i = 0; i < pos.length; i++) {
				pos[i] = new Word(Opcode.___, -1, -1, -1);
			}
			; // cada posicao da memoria inicializada
		}
	}

	public class Word {    // cada posicao da memoria tem uma instrucao (ou um dado)
		public Opcode opc; //
		public int ra;     // indice do primeiro registrador da operacao (Rs ou Rd cfe opcode na tabela)
		public int rb;     // indice do segundo registrador da operacao (Rc ou Rs cfe operacao)
		public int p;      // parametro para instrucao (k ou A cfe operacao), ou o dado, se opcode = DADO

		public Word(Opcode _opc, int _ra, int _rb, int _p) { // vide definição da VM - colunas vermelhas da tabela
			opc = _opc;
			ra = _ra;
			rb = _rb;
			p  = _p;
		}

		public void copia(Word w) { // copia conteúdo (usado em carga, DMA e swap)
			opc = w.opc;
			ra = w.ra;
			rb = w.rb;
			p  = w.p;
		}

		public void limpa() {
			opc = Opcode.___;
			ra = -1;
			rb = -1;
			p  = -1;
		}
	}

	// -------------------------------------------------------------------------------------------------------
	// --------------------- C P U - definicoes da CPU
	// -----------------------------------------------------

	public enum Opcode {
		DATA, ___,              // se memoria nesta posicao tem um dado, usa DATA, se nao usada ee NULO ___
		JMP, JMPI, JMPIG, JMPIL, JMPIE, // desvios
		JMPIM, JMPIGM, JMPILM, JMPIEM,
		JMPIGK, JMPILK, JMPIEK, JMPIGT,
		ADDI, SUBI, ADD, SUB, MULT,    // matematicos
		LDI, LDD, STD, LDX, STX, MOVE, // movimentacao
		SYSCALL, STOP                  // chamada de sistema e parada
	}

	public enum Interrupts {           // possiveis interrupcoes
		noInterrupt, intEnderecoInvalido, intInstrucaoInvalida, intOverflow,
		intTimer,                      // fim da fatia de tempo (relógio = contador de instruções)
		intProcessoRemovido,           // usuário pediu "rm" do processo que está na CPU
		intPageFault,                  // T2b: página do endereço lógico não está na memória
		// interrupções externas, geradas pelos dispositivos:
		intIO,                         // T2a: Console terminou um pedido de IN/OUT
		intPaginaSalva,                // T2b: Disco terminou de salvar página vitimada (frame liberado)
		intPaginaCarregada;            // T2b: Disco terminou de carregar página num frame
	}

	// interrupção sinalizada por um dispositivo, com o pedido que foi atendido
	public class InterrupcaoExterna {
		public Interrupts tipo;
		public Object pedido;

		public InterrupcaoExterna(Interrupts _tipo, Object _pedido) {
			tipo = _tipo;
			pedido = _pedido;
		}
	}

	public class CPU {
		private int maxInt; // valores maximo e minimo para inteiros nesta cpu
		private int minInt;
		                    // CONTEXTO da CPU ...
		private int pc;     // ... composto de program counter (endereço LÓGICO),
		private Word ir;    // instruction register,
		private int[] reg;  // registradores da CPU
		private Interrupts irpt; // durante instrucao, interrupcao pode ser sinalizada
		private EntradaTP[] tabPag; // tabela de páginas do processo em execução (MMU)
		private int paginaFalta;    // página que gerou page fault (lida pela rotina de tratamento)
		                    // FIM CONTEXTO DA CPU

		private Word[] m;   // m é o array de memória "física", CPU tem uma ref a m para acessar
		private int tamPg;  // tamanho de página (= tamanho de frame)

		// "variável" da CPU onde os dispositivos sinalizam interrupção (int IO / int IO-VM).
		// é uma fila para que interrupções simultâneas de dispositivos diferentes não se percam.
		private final ConcurrentLinkedQueue<InterrupcaoExterna> irptExterna = new ConcurrentLinkedQueue<>();

		private InterruptHandling ih;    // significa desvio para rotinas de tratamento de Int - se int ligada, desvia
		private SysCallHandling sysCall; // significa desvio para tratamento de chamadas de sistema

		                            // relógio: a cada "delta" instruções liga interrupção de timer
		private int delta;
		private int contInstr;

		private boolean ativa;                 // true enquanto há processo despachado na CPU
		private boolean completouInstr;        // processo já completou instrução nesta fatia
		private volatile boolean pedidoRemocao; // "rm" do processo em execução
		private volatile int atraso = 0;       // ms entre instruções (para acompanhar a execução)

		                            // auxilio aa depuração
		private volatile boolean debug; // se true entao mostra cada instrucao em execucao
		private Utilities u;        // para debug (dump)

		public CPU(Memory _mem, int _tamPg, int _delta, boolean _debug) { // ref a MEMORIA passada na criacao da CPU
			maxInt = 32767;            // capacidade de representacao modelada
			minInt = -32767;           // se exceder deve gerar interrupcao de overflow
			m = _mem.pos;              // usa o atributo 'm' para acessar a memoria, só para ficar mais pratico
			reg = new int[10];         // aloca o espaço dos registradores - regs 8 e 9 usados somente para IO
			tamPg = _tamPg;
			delta = _delta;
			debug = _debug;            // se true, print da instrucao em execucao
		}

		public void setAddressOfHandlers(InterruptHandling _ih, SysCallHandling _sysCall) {
			ih = _ih;                  // aponta para rotinas de tratamento de int
			sysCall = _sysCall;        // aponta para rotinas de tratamento de chamadas de sistema
		}

		public void setUtilities(Utilities _u) {
			u = _u;                     // aponta para rotinas utilitárias - fazer dump da memória na tela
		}

		// chamado por um dispositivo (outra thread) para interromper a CPU.
		// libera a CPU caso esteja ociosa, para que trate a interrupção.
		public void interrompe(Interrupts tipo, Object pedido) {
			irptExterna.add(new InterrupcaoExterna(tipo, pedido));
			so.semaCPU.release();
		}

		// MMU: traducao de endereco logico -> fisico, com controle de acesso indevido.
		// retorna o endereço físico, ou -1 e liga:
		//    intEnderecoInvalido  se o endereço não pertence a nenhuma página do processo
		//    intPageFault         se a página existe mas não está em memória
		private int traduz(int e) {
			if (tabPag != null && e >= 0) {
				int pagina = e / tamPg;
				int deslocamento = e % tamPg;
				if (pagina < tabPag.length) {
					EntradaTP t = tabPag[pagina];
					if (t.estado == EstadoPagina.MEMORIA) {
						return t.frame * tamPg + deslocamento;
					}
					irpt = Interrupts.intPageFault;    // página válida, mas fora da memória
					paginaFalta = pagina;
					return -1;
				}
			}
			irpt = Interrupts.intEnderecoInvalido;    // liga interrupcao no meio da exec da instrucao
			return -1;
		}

		private boolean testOverflow(int v) {             // toda operacao matematica deve avaliar se ocorre overflow
			if ((v < minInt) || (v > maxInt)) {
				irpt = Interrupts.intOverflow;            // se houver liga interrupcao no meio da exec da instrucao
				return false;
			}
			;
			return true;
		}

		// restaura o contexto de um processo na CPU (feito pelo escalonador)
		public void setContext(int _pc, int[] _reg, EntradaTP[] _tabPag) {
			pc = _pc;
			System.arraycopy(_reg, 0, reg, 0, reg.length);
			tabPag = _tabPag;
			irpt = Interrupts.noInterrupt;                // reset da interrupcao registrada
			contInstr = 0;                                // reinicia a fatia de tempo
			completouInstr = false;
			ativa = true;
		}

		// salva o contexto da CPU no PCB (feito pelas rotinas de tratamento)
		public void saveContext(PCB p) {
			p.pc = pc;
			System.arraycopy(reg, 0, p.reg, 0, reg.length);
		}

		// Uma instrução que gera page fault não altera nenhum registrador, memória ou pc
		// antes da tradução falhar; assim, quando o processo voltar a executar, a mesma
		// instrução é reexecutada (instruction restart).
		// um ciclo de instrução: fetch, execução e verificação de interrupção
		// deve ser chamado com o lock do SO adquirido
		private void passo() {
			if (pedidoRemocao) {                          // "rm" do processo rodando
				pedidoRemocao = false;
				ih.handle(Interrupts.intProcessoRemovido);
				return;
			}
			irpt = Interrupts.noInterrupt;
			PCB atual = so.running;
			// --------------------------------------------------------------------------------------------------
			// FASE DE FETCH
			int pcFis = traduz(pc);
			if (pcFis >= 0) { // pc valido
				ir = m[pcFis];  // <<<<<<<<<<<< AQUI faz FETCH - busca posicao da memoria apontada por pc, guarda em ir
				             // resto é dump de debug
				if (debug) {
					System.out.print("                                              regs: ");
					for (int i = 0; i < 10; i++) {
						System.out.print(" r[" + i + "]:" + reg[i]);
					}
					;
					System.out.println();
				}
				if (debug) {
					System.out.print("          [P" + sysCall.idRodando() + "] pc: " + pc + " (fis " + pcFis + ")   exec: ");
					u.dump(ir);
				}

			// --------------------------------------------------------------------------------------------------
			// FASE DE EXECUCAO DA INSTRUCAO CARREGADA NO ir
				int f; // endereço físico calculado na instrução
				switch (ir.opc) {       // conforme o opcode (código de operação) executa

					// Instrucoes de Busca e Armazenamento em Memoria
					case LDI: // Rd ← k        veja a tabela de instrucoes do HW simulado para entender a semantica da instrucao
						reg[ir.ra] = ir.p;
						pc++;
						break;
					case LDD: // Rd <- [A]
						f = traduz(ir.p);
						if (f >= 0) {
							reg[ir.ra] = m[f].p;
							pc++;
						}
						break;
					case LDX: // RD <- [RS] // NOVA
						f = traduz(reg[ir.rb]);
						if (f >= 0) {
							reg[ir.ra] = m[f].p;
							pc++;
						}
						break;
					case STD: // [A] ← Rs
						f = traduz(ir.p);
						if (f >= 0) {
							m[f].opc = Opcode.DATA;
							m[f].p = reg[ir.ra];
							pc++;
							if (debug)
							    {   System.out.print("                                  " + f + ":  ");
								    u.dump(m[f]);
								}
							}
						break;
					case STX: // [Rd] ←Rs
						f = traduz(reg[ir.ra]);
						if (f >= 0) {
							m[f].opc = Opcode.DATA;
							m[f].p = reg[ir.rb];
							pc++;
						}
						;
						break;
					case MOVE: // RD <- RS
						reg[ir.ra] = reg[ir.rb];
						pc++;
						break;
					// Instrucoes Aritmeticas
					case ADD: // Rd ← Rd + Rs
						reg[ir.ra] = reg[ir.ra] + reg[ir.rb];
						testOverflow(reg[ir.ra]);
						pc++;
						break;
					case ADDI: // Rd ← Rd + k
						reg[ir.ra] = reg[ir.ra] + ir.p;
						testOverflow(reg[ir.ra]);
						pc++;
						break;
					case SUB: // Rd ← Rd - Rs
						reg[ir.ra] = reg[ir.ra] - reg[ir.rb];
						testOverflow(reg[ir.ra]);
						pc++;
						break;
					case SUBI: // RD <- RD - k // NOVA
						reg[ir.ra] = reg[ir.ra] - ir.p;
						testOverflow(reg[ir.ra]);
						pc++;
						break;
					case MULT: // Rd <- Rd * Rs
						reg[ir.ra] = reg[ir.ra] * reg[ir.rb];
						testOverflow(reg[ir.ra]);
						pc++;
						break;

					// Instrucoes JUMP
					case JMP: // PC <- k
						pc = ir.p;
						break;
					case JMPIM: // PC <- [A]
						f = traduz(ir.p);
						if (f >= 0) {
							pc = m[f].p;
						}
						break;
					case JMPIG: // If Rc > 0 Then PC ← Rs Else PC ← PC +1
						if (reg[ir.rb] > 0) {
							pc = reg[ir.ra];
						} else {
							pc++;
						}
						break;
					case JMPIGK: // If RC > 0 then PC <- k else PC++
						if (reg[ir.rb] > 0) {
							pc = ir.p;
						} else {
							pc++;
						}
						break;
					case JMPILK: // If RC < 0 then PC <- k else PC++
						if (reg[ir.rb] < 0) {
							pc = ir.p;
						} else {
							pc++;
						}
						break;
					case JMPIEK: // If RC = 0 then PC <- k else PC++
						if (reg[ir.rb] == 0) {
							pc = ir.p;
						} else {
							pc++;
						}
						break;
					case JMPIL: // if Rc < 0 then PC <- Rs Else PC <- PC +1
						if (reg[ir.rb] < 0) {
							pc = reg[ir.ra];
						} else {
							pc++;
						}
						break;
					case JMPIE: // If Rc = 0 Then PC <- Rs Else PC <- PC +1
						if (reg[ir.rb] == 0) {
							pc = reg[ir.ra];
						} else {
							pc++;
						}
						break;
					case JMPIGM: // If RC > 0 then PC <- [A] else PC++
						f = traduz(ir.p);
					    if (f >= 0){
						    if (reg[ir.rb] > 0) {
							   pc = m[f].p;
						    } else {
							  pc++;
						   }
					    }
						break;
					case JMPILM: // If RC < 0 then PC <- [A] else PC++
						if (reg[ir.rb] < 0) {
							f = traduz(ir.p);
							if (f >= 0) {
								pc = m[f].p;
							}
						} else {
							pc++;
						}
						break;
					case JMPIEM: // If RC = 0 then PC <- [A] else PC++
						if (reg[ir.rb] == 0) {
							f = traduz(ir.p);
							if (f >= 0) {
								pc = m[f].p;
							}
						} else {
							pc++;
						}
						break;
					case JMPIGT: // If RS>RC then PC <- k else PC++
						if (reg[ir.ra] > reg[ir.rb]) {
							pc = ir.p;
						} else {
							pc++;
						}
						break;

					case DATA: // pc está sobre área supostamente de dados
						irpt = Interrupts.intInstrucaoInvalida;
						break;

					// Chamadas de sistema
					case SYSCALL:
						sysCall.handle(); // <<<<< aqui desvia para rotina de chamada de sistema (IO).
						                  // a rotina avança o pc e bloqueia o processo, ou liga
						                  // interrupção (page fault / endereço inválido) sem avançar o pc
						break;

					case STOP: // chamada de sistema: fim do processo
						sysCall.stop();
						break;

					// Inexistente
					default:
						irpt = Interrupts.intInstrucaoInvalida;
						break;
				}
			}
			contInstr++;
			if (irpt != Interrupts.intPageFault && !completouInstr && atual != null) {
				completouInstr = true;
				so.gm.limpaNovos(atual);                  // páginas carregadas já foram usadas
				so.atendePedidosFrame();                  // podem agora ser vitimadas por quem espera
			}
			// --------------------------------------------------------------------------------------------------
			// VERIFICA INTERRUPÇÃO !!! - TERCEIRA FASE DO CICLO DE INSTRUÇÕES
			if (irpt != Interrupts.noInterrupt) {     // existe interrupção
				ih.handle(irpt);                      // desvia para rotina de tratamento - esta rotina é do SO
			} else if (ativa && contInstr >= delta) {
				ih.handle(Interrupts.intTimer);       // completou a fatia: liga interrupção de relógio
			}
		}

		// trata interrupções sinalizadas pelos dispositivos (int IO / int IO-VM).
		// deve ser chamado com o lock do SO adquirido
		private void trataInterrupcoesExternas() {
			InterrupcaoExterna ie;
			while ((ie = irptExterna.poll()) != null) {
				ih.handleExterna(ie);
			}
		}

		// ------------------------------------------------------------------------------------------------------
		// Thread CPU: aguarda bloqueada até o escalonador despachar um processo ou um
		// dispositivo interromper; trata interrupções externas, e executa instruções
		// enquanto houver processo na CPU; volta a aguardar.
		public void loopCPU() {
			while (true) {
				so.semaCPU.acquireUninterruptibly();          // aguarda bloqueada (semaCPU.wait)
				while (true) {
					so.lock.lock();
					try {
						trataInterrupcoesExternas();          // se tem interrupção de dispositivo, desvia para rotina
						if (!ativa) {
							break;                            // sem processo: volta ao loop de fora (aguarda)
						}
						passo();
					} finally {
						so.lock.unlock();
					}
					if (atraso > 0) {
						try { Thread.sleep(atraso); } catch (InterruptedException e) { }
					}
				}
			}
		}
	}
	// ------------------ C P U - fim
	// -----------------------------------------------------------------------
	// ------------------------------------------------------------------------------------------------------

	// ------------------ D I S P O S I T I V O S
	// -----------------------------------------------------------------------

	// pedido para o Console: <pid, IN/OUT, end>
	public class PedidoConsole {
		public PCB p;
		public int tipo;      // 1 = IN, 2 = OUT
		public int endLogico; // endereço lógico informado em reg[9]
		public int endFisico; // endereço físico (frame fixado na memória enquanto o pedido está pendente)

		public PedidoConsole(PCB _p, int _tipo, int _endLogico, int _endFisico) {
			p = _p;
			tipo = _tipo;
			endLogico = _endLogico;
			endFisico = _endFisico;
		}
	}

	// Thread Console: loop sempre - aguarda pedido na fila, atende (IN lê do usuário e
	// escreve na memória por DMA; OUT lê da memória por DMA e escreve na tela),
	// e interrompe a CPU (intIO).
	public class Console {
		public final BlockingQueue<PedidoConsole> filaPedidos = new LinkedBlockingQueue<>(); // Fila Pedidos Console
		public final BlockingQueue<Integer> entradas = new LinkedBlockingQueue<>(); // valores digitados no shell
		public volatile PCB aguardandoIN = null;     // processo cujo IN está sendo atendido
		public volatile int atraso = 0;              // ms de latência do dispositivo

		public void loop() {
			while (true) {
				PedidoConsole ped;
				try {
					ped = filaPedidos.take();       // aguarda pedido na entrada (consumidor)
				} catch (InterruptedException e) {
					continue;
				}
				if (ped.p.terminado) continue;      // processo removido enquanto esperava
				if (atraso > 0) {
					try { Thread.sleep(atraso); } catch (InterruptedException e) { }
				}
				if (ped.tipo == 1) {                 // IN: pede ao usuário (o shell repassa o valor)
					aguardandoIN = ped.p;
					System.out.println("IN [processo " + ped.p.id + "]: digite um inteiro");
					int v;
					try {
						v = entradas.take();
					} catch (InterruptedException e) {
						v = 0;
					}
					aguardandoIN = null;
					so.lock.lock();                  // DMA: escreve na memória do processo
					try {
						if (!ped.p.terminado) {
							hw.mem.pos[ped.endFisico].opc = Opcode.DATA;
							hw.mem.pos[ped.endFisico].p = v;
						}
					} finally {
						so.lock.unlock();
					}
				} else {                             // OUT: DMA lê da memória do processo
					int v = 0;
					boolean vivo;
					so.lock.lock();
					try {
						vivo = !ped.p.terminado;
						if (vivo) v = hw.mem.pos[ped.endFisico].p;
					} finally {
						so.lock.unlock();
					}
					if (vivo) System.out.println("OUT [processo " + ped.p.id + "]:   " + v);
				}
				hw.cpu.interrompe(Interrupts.intIO, ped);  // interrompe cpu: fim do IO
			}
		}
	}

	// pedido para o Disco (IO-VM)
	public class PedidoDisco {
		public static final int CARREGA = 1;  // traz página (do programa ou do swap) para um frame
		public static final int SALVA = 2;    // salva frame (página vitimada) no swap

		public int op;
		public PCB p;          // processo que pediu (o que sofreu page fault)
		public int pagina;     // página de p a carregar
		public int frame;      // frame destino (CARREGA) ou origem (SALVA)
		public int slot;       // slot do swap: origem (CARREGA de swap, -1 = do programa) ou destino (SALVA)
		public String nomePrograma;
		public PCB vitima;     // SALVA: dono da página vitimada
		public int paginaVitima;

		public PedidoDisco(int _op, PCB _p, int _pagina, int _frame, int _slot) {
			op = _op;
			p = _p;
			pagina = _pagina;
			frame = _frame;
			slot = _slot;
			nomePrograma = _p.nome;
		}
	}

	// Disco: contém os programas armazenados e a área de swap (cópias de frames vitimados)
	public class Disco {
		public final Word[][] swap;           // swap[s] = conteúdo salvo de um frame
		public final BlockingQueue<PedidoDisco> filaPedidos = new LinkedBlockingQueue<>(); // Fila Pedidos IO-VM
		public volatile int atraso = 20;      // ms de latência de cada acesso ao disco

		public Disco(int nroSlots, int tamPg) {
			swap = new Word[nroSlots][tamPg];
			for (Word[] s : swap) {
				for (int i = 0; i < tamPg; i++) s[i] = new Word(Opcode.___, -1, -1, -1);
			}
		}

		// Thread IO-VM: loop sempre - aguarda pedido na entrada, serve entrada de página -> frame
		// (ou frame -> swap), interrompe a CPU.  A fila é FIFO: um pedido de carga de uma
		// página é sempre atendido depois do salvamento dessa mesma página.
		public void loop() {
			int tamPg = so.gm.tamPg;
			while (true) {
				PedidoDisco ped;
				try {
					ped = filaPedidos.take();
				} catch (InterruptedException e) {
					continue;
				}
				if (atraso > 0) {
					try { Thread.sleep(atraso); } catch (InterruptedException e) { }
				}
				Word[] m = hw.mem.pos;
				int ini = ped.frame * tamPg;
				so.lock.lock();                      // DMA entre disco e memória
				try {
					if (ped.op == PedidoDisco.SALVA) {
						for (int i = 0; i < tamPg; i++) swap[ped.slot][i].copia(m[ini + i]);
					} else if (ped.slot >= 0) {      // página já vitimada antes: vem do swap
						for (int i = 0; i < tamPg; i++) m[ini + i].copia(swap[ped.slot][i]);
					} else {                         // página nunca carregada: vem do programa
						Word[] prog = progs.retrieveProgram(ped.nomePrograma);
						for (int i = 0; i < tamPg; i++) {
							int l = ped.pagina * tamPg + i;
							if (prog != null && l < prog.length) m[ini + i].copia(prog[l]);
							else m[ini + i].limpa();
						}
					}
				} finally {
					so.lock.unlock();
				}
				hw.cpu.interrompe(ped.op == PedidoDisco.SALVA ? Interrupts.intPaginaSalva
						: Interrupts.intPaginaCarregada, ped);
			}
		}
	}

	// ------------------- HW - constituido de CPU, MEMORIA e dispositivos (Console e Disco)
	// -----------------------------------------------
	public class HW {
		public Memory mem;
		public CPU cpu;
		public Console console;
		public Disco disco;

		public HW(int tamMem, int tamPg, int delta) {
			mem = new Memory(tamMem);
			cpu = new CPU(mem, tamPg, delta, false); // traceOn liga debug
			console = new Console();
			disco = new Disco(Math.max(64, 4 * (tamMem / tamPg)), tamPg);
		}
	}
	// -------------------------------------------------------------------------------------------------------

	// --------------------H A R D W A R E - fim
	// -------------------------------------------------------------
	// -------------------------------------------------------------------------------------------------------

	// ///////////////////////////////////////////////////////////////////////////////////////////////////////

	// -------------------------------------------------------------------------------------------------------
	// -------------------------------------------------------------------------------------------------------
	// ------------------- SW - inicio - Sistema Operacional
	// -------------------------------------------------

	// ------------------- I N T E R R U P C O E S - rotinas de tratamento
	// ----------------------------------
	public class InterruptHandling {
		private HW hw; // referencia ao hw se tiver que setar algo

		public InterruptHandling(HW _hw) {
			hw = _hw;
		}

		// interrupções geradas pela CPU durante a execução do processo rodando
		public void handle(Interrupts irpt) {
			if (irpt == Interrupts.intTimer) {
				so.rotTimer();                         // Rot Trat TIMER
			} else if (irpt == Interrupts.intPageFault) {
				so.rotPageFault(hw.cpu.paginaFalta);   // Rot Page Fault
			} else {                                   // Rot Tratamento: overflow, acesso indevido, etc.
				System.out.println("                                               Interrupcao " + irpt
						+ "   processo: " + so.running.id + "   pc: " + hw.cpu.pc);
				so.finalizaRunning();                  // finaliza processo e libera escalonador
			}
		}

		// interrupções geradas pelos dispositivos
		public void handleExterna(InterrupcaoExterna ie) {
			switch (ie.tipo) {
				case intIO:              so.rotRetIO((PedidoConsole) ie.pedido);           break; // Rot Trat Ret IO
				case intPaginaSalva:     so.rotPaginaSalva((PedidoDisco) ie.pedido);       break; // Rot Trat Ret IO-VM
				case intPaginaCarregada: so.rotPaginaCarregada((PedidoDisco) ie.pedido);   break; // Rot Trat Ret IO-VM
				default: break;
			}
		}
	}

	// ------------------- C H A M A D A S D E S I S T E M A - rotinas de tratamento
	// ----------------------
	public class SysCallHandling {
		private HW hw; // referencia ao hw se tiver que setar algo

		public SysCallHandling(HW _hw) {
			hw = _hw;
		}

		public int idRodando() {
			return so.running == null ? -1 : so.running.id;
		}

		public void stop() { // chamada de sistema indicando final de programa
			if (so.log) System.out.println("                                               SYSCALL STOP   processo: " + idRodando());
			so.finalizaRunning();
		}

		// Chamada IO: reg[8] = 1 (IN) ou 2 (OUT),  reg[9] = endereço lógico do inteiro
		public void handle() {
			if (so.log) System.out.println("SYSCALL pars:  " + hw.cpu.reg[8] + " / " + hw.cpu.reg[9]
					+ "   processo: " + idRodando());
			int tipo = hw.cpu.reg[8];
			if (tipo != 1 && tipo != 2) {
				System.out.println("  PARAMETRO INVALIDO");
				hw.cpu.pc++;
				return;
			}
			// a página do endereço precisa estar em memória para o DMA: se não estiver,
			// a tradução liga page fault e a SYSCALL é reexecutada depois da carga
			int f = hw.cpu.traduz(hw.cpu.reg[9]);
			if (f < 0) return;
			hw.cpu.pc++;                                        // retorna para a instrução seguinte
			so.chamadaIO(new PedidoConsole(so.running, tipo, hw.cpu.reg[9], f));
		}
	}

	// ------------------- T A B E L A   D E   P Á G I N A S (estendida para memória virtual)
	// ----------------------------------
	public enum EstadoPagina {
		NAO_CARREGADA,   // nunca foi carregada: conteúdo é a página do programa original (no disco)
		MEMORIA,         // está num frame da memória
		DISCO,           // foi vitimada: cópia está no swap (slot)
		EM_CARGA         // page fault em tratamento: frame reservado, aguardando o disco
	}

	public class EntradaTP {
		public EstadoPagina estado = EstadoPagina.NAO_CARREGADA;
		public int frame = -1;   // válido em MEMORIA / EM_CARGA
		public int slot = -1;    // slot do swap, se a página já foi salva alguma vez

		public String toString() {
			switch (estado) {
				case MEMORIA:  return "f" + frame;
				case EM_CARGA: return "f" + frame + "(carga)";
				case DISCO:    return "swap" + slot;
				default:       return "-";
			}
		}
	}

	// ------------------- G E R E N T E   D E   M E M O R I A  (paginação + memória virtual)
	// ----------------------------------
	public class GM {
		private int tamPg;          // tamanho de página = tamanho de frame
		private int nroFrames;      // tamMem / tamPg
		private boolean[] livre;    // livre[f] == true se o frame f está disponível
		private PCB[] dono;         // processo dono do frame (ou que o reservou)
		private int[] paginaDe;     // página hospedada (ou a hospedar) no frame
		private boolean[] emTransito; // frame reservado para page fault em andamento (salvando / carregando)
		private int[] fixoIO;       // nro de pedidos de IO (DMA) pendentes no frame: não pode ser vitimado
		private boolean[] novo;     // página recém-carregada, dono ainda não completou instrução: evita
		                            // vitimá-la antes de ser usada (senão a FIFO pura entra em thrashing)
		private LinkedList<Integer> fifo = new LinkedList<>(); // ordem de carga: política de vitimação FIFO

		public GM(int tamMem, int _tamPg) {
			tamPg = _tamPg;
			nroFrames = tamMem / tamPg;
			livre = new boolean[nroFrames];
			Arrays.fill(livre, true);
			dono = new PCB[nroFrames];
			paginaDe = new int[nroFrames];
			emTransito = new boolean[nroFrames];
			fixoIO = new int[nroFrames];
			novo = new boolean[nroFrames];
		}

		// aloca um frame livre para a página pg do processo p; -1 se não há frame livre
		public int alocaFrame(PCB p, int pg) {
			for (int f = 0; f < nroFrames; f++) {
				if (livre[f]) {
					livre[f] = false;
					dono[f] = p;
					paginaDe[f] = pg;
					return f;
				}
			}
			return -1;
		}

		// página foi carregada no frame f: entra no fim da fila FIFO
		public void paginaPresente(int f) {
			emTransito[f] = false;
			novo[f] = true;
			fifo.remove(Integer.valueOf(f));
			fifo.addLast(f);
		}

		// o processo completou uma instrução: suas páginas deixam de ser "novas"
		public void limpaNovos(PCB p) {
			for (EntradaTP t : p.tabelaPaginas) {
				if (t.estado == EstadoPagina.MEMORIA) novo[t.frame] = false;
			}
		}

		// Política de vitimação: FIFO - a página carregada há mais tempo.
		// Nunca são vítimas: frames com page fault em andamento ou com DMA pendente.
		// 1a passada: só páginas já usadas pelo dono (não "novas"). Um processo mantém as
		//    páginas que acabou de receber até completar uma instrução; sem isto a FIFO pura
		//    entra em thrashing sem progresso quando há muitos processos e poucos frames.
		// 2a passada (só em impasse: nada rodando/pronto e nada em trânsito, ou seja, nenhum
		//    evento futuro liberaria frame): aceita página nova de outro processo bloqueado.
		// retorna -1 se não há candidato (o pedido aguarda um frame ser liberado).
		public int escolheVitima(PCB requisitante, boolean impasse) {
			for (int passada = 0; passada < (impasse ? 2 : 1); passada++) {
				for (Iterator<Integer> it = fifo.iterator(); it.hasNext();) {
					int f = it.next();
					if (emTransito[f] || fixoIO[f] > 0) continue;
					boolean ok = !novo[f] || (passada == 1 && dono[f] != requisitante
							&& dono[f].estado.equals("bloqueado"));
					if (ok) {
						it.remove();
						return f;
					}
				}
			}
			return -1;
		}

		// reserva o frame f (vitimado) para a página pg do processo p
		public void reserva(int f, PCB p, int pg) {
			dono[f] = p;
			paginaDe[f] = pg;
			emTransito[f] = true;
		}

		public boolean algumEmTransito() {
			for (boolean b : emTransito) if (b) return true;
			return false;
		}

		public void liberaFrame(int f) {
			livre[f] = true;
			dono[f] = null;
			emTransito[f] = false;
			fixoIO[f] = 0;
			novo[f] = false;
			fifo.remove(Integer.valueOf(f));
		}

		// libera os frames das páginas em memória do processo
		// (frames em trânsito são liberados quando o disco termina o pedido)
		public void desaloca(EntradaTP[] tabela) {
			for (EntradaTP t : tabela) {
				if (t.estado == EstadoPagina.MEMORIA) {
					liberaFrame(t.frame);
				}
			}
		}

		public int framesLivres() {
			int n = 0;
			for (boolean b : livre) {
				if (b) n++;
			}
			return n;
		}

		public int inicioFrame(int f) { return f * tamPg; }
		public int fimFrame(int f)    { return (f + 1) * tamPg - 1; }
	}

	// ------------------- G E R E N T E   D O   D I S C O (área de swap)
	// aloca e libera slots, como o GM faz com frames
	// ----------------------------------
	public class GD {
		private boolean[] livre;

		public GD(int nroSlots) {
			livre = new boolean[nroSlots];
			Arrays.fill(livre, true);
		}

		public int aloca() {
			for (int s = 0; s < livre.length; s++) {
				if (livre[s]) {
					livre[s] = false;
					return s;
				}
			}
			return -1;
		}

		public void libera(int s) {
			if (s >= 0) livre[s] = true;
		}

		public int livres() {
			int n = 0;
			for (boolean b : livre) if (b) n++;
			return n;
		}

		public int total() { return livre.length; }
	}

	// ------------------- P C B
	// ----------------------------------
	public class PCB {
		public int id;
		public String nome;            // nome do programa
		public int tamanho;            // nro de palavras do programa
		public EntradaTP[] tabelaPaginas;
		public int pc = 0;             // contexto salvo da CPU
		public int[] reg = new int[10];
		public String estado;          // "pronto", "rodando" ou "bloqueado"
		public String motivoBloqueio = "";  // "IO" (console) ou "VM" (page fault)
		public boolean liberado;       // liberado para executar por exec/execAll (modo não contínuo)
		public volatile boolean terminado;
		public int pageFaults = 0;

		public PCB(int _id, String _nome, int _tamanho, int nroPaginas) {
			id = _id;
			nome = _nome;
			tamanho = _tamanho;
			tabelaPaginas = new EntradaTP[nroPaginas];
			for (int i = 0; i < nroPaginas; i++) tabelaPaginas[i] = new EntradaTP();
			estado = "pronto";
		}

		// frames usados pelo processo: página -> frame / swap / não carregada
		public String mapa() {
			StringBuilder sb = new StringBuilder("[");
			for (int i = 0; i < tabelaPaginas.length; i++) {
				if (i > 0) sb.append(", ");
				sb.append("p").append(i).append(":").append(tabelaPaginas[i]);
			}
			return sb.append("]").toString();
		}

		public String estadoCompleto() {
			return estado.equals("bloqueado") ? "bloqueado(" + motivoBloqueio + ")" : estado;
		}
	}

	// ------------------- G E R E N T E   D E   P R O C E S S O S
	// ----------------------------------
	public class GP {
		private int proxId = 1;
		public LinkedList<PCB> processos = new LinkedList<>();  // todos os processos vivos

		// cria processo; retorna o id, ou -1 se não existe o programa.
		// Memória virtual: somente a página 0 é carregada (se houver frame livre;
		// caso contrário, a primeira busca de instrução gera page fault).
		public int criaProcesso(String nomePrograma) {
			Word[] programa = progs.retrieveProgram(nomePrograma);
			if (programa == null) {
				System.out.println("programa inexistente: " + nomePrograma);
				return -1;
			}
			int nroPaginas = (programa.length + so.gm.tamPg - 1) / so.gm.tamPg;
			PCB p = new PCB(proxId++, nomePrograma, programa.length, nroPaginas);
			int f = so.gm.alocaFrame(p, 0);                  // pede um frame ao GM
			if (f >= 0) {
				so.utils.cargaPagina(programa, 0, f);         // carrega a primeira página
				p.tabelaPaginas[0].estado = EstadoPagina.MEMORIA;
				p.tabelaPaginas[0].frame = f;
				so.gm.paginaPresente(f);
			}
			processos.add(p);
			so.ready.addLast(p);                             // coloca na fila de prontos
			if (so.log) System.out.println("  [GP] processo " + p.id + " (" + nomePrograma + ") criado, "
					+ nroPaginas + " paginas, frames " + p.mapa());
			if (so.running == null) {
				so.semaSch.release();                        // se não há processo rodando, libera o escalonador
			}
			return p.id;
		}

		// desaloca processo com id: retira de fila, libera memória e pcb.
		// se for o processo em execução, pede à CPU que o interrompa (o tratamento
		// da interrupção finaliza o processo e chama o escalonador)
		public boolean desalocaProcesso(int id) {
			if (so.running != null && so.running.id == id) {
				so.hw.cpu.pedidoRemocao = true;
				return true;
			}
			PCB p = busca(id);
			if (p == null) {
				return false;
			}
			libera(p);
			return true;
		}

		// finalização: desaloca frames e swap, retira de filas
		public void libera(PCB p) {
			p.terminado = true;
			so.ready.remove(p);
			so.bloqueadosConsole.remove(p);
			so.bloqueadosVM.remove(p);
			so.pedidosFrame.removeIf(pf -> pf.p == p);
			processos.remove(p);
			so.gm.desaloca(p.tabelaPaginas);
			for (EntradaTP t : p.tabelaPaginas) so.gd.libera(t.slot);
			so.fimProcesso.signalAll();
			so.atendePedidosFrame();                       // frames liberados podem atender quem espera
		}

		public PCB busca(int id) {
			for (PCB p : processos) {
				if (p.id == id) return p;
			}
			return null;
		}
	}

	// ------------------ U T I L I T A R I O S D O S I S T E M A
	// -----------------------------------------
	public class Utilities {
		private HW hw;

		public Utilities(HW _hw) {
			hw = _hw;
		}

		// carga de uma página do programa no frame f
		public void cargaPagina(Word[] p, int pagina, int f) {
			Word[] m = hw.mem.pos; // m[] é o array de posições memória do hw
			int tamPg = so.gm.tamPg;
			for (int i = 0; i < tamPg; i++) {
				int l = pagina * tamPg + i;
				if (l < p.length) m[f * tamPg + i].copia(p[l]);
				else m[f * tamPg + i].limpa();
			}
		}

		// dump da memória
		public void dump(Word w) { // funcoes de DUMP nao existem em hardware - colocadas aqui para facilidade
			System.out.print("[ ");
			System.out.print(w.opc);
			System.out.print(", ");
			System.out.print(w.ra);
			System.out.print(", ");
			System.out.print(w.rb);
			System.out.print(", ");
			System.out.print(w.p);
			System.out.println("  ] ");
		}

		public void dump(int ini, int fim) {
			Word[] m = hw.mem.pos; // m[] é o array de posições memória do hw
			ini = Math.max(0, ini);
			fim = Math.min(m.length, fim);
			for (int i = ini; i < fim; i++) {
				System.out.print(i);
				System.out.print(":  ");
				dump(m[i]);
			}
		}

		// dump do PCB e da memória (lógica) do processo, onde quer que cada página esteja
		public void dumpProcesso(PCB p) {
			int tamPg = so.gm.tamPg;
			System.out.println("PCB  id: " + p.id + "   programa: " + p.nome + "   estado: " + p.estadoCompleto()
					+ "   tamanho: " + p.tamanho + " palavras   page faults: " + p.pageFaults);
			System.out.println("     pc: " + p.pc + "   regs: " + Arrays.toString(p.reg));
			System.out.println("     tabela de paginas: " + p.mapa());
			Word[] prog = progs.retrieveProgram(p.nome);
			for (int pg = 0; pg < p.tabelaPaginas.length; pg++) {
				EntradaTP t = p.tabelaPaginas[pg];
				String onde;
				switch (t.estado) {
					case MEMORIA:  onde = "em memoria, frame " + t.frame + "  [" + so.gm.inicioFrame(t.frame) + ".." + so.gm.fimFrame(t.frame) + "]"; break;
					case DISCO:    onde = "no disco (swap slot " + t.slot + ")"; break;
					case EM_CARGA: onde = "sendo carregada no frame " + t.frame; break;
					default:       onde = "nunca carregada (conteudo = programa original)";
				}
				System.out.println("  --- pagina " + pg + "  " + onde);
				for (int d = 0; d < tamPg; d++) {
					int l = pg * tamPg + d;
					Word w;
					String loc;
					if (t.estado == EstadoPagina.MEMORIA) {
						w = hw.mem.pos[t.frame * tamPg + d];
						loc = "fis " + (t.frame * tamPg + d);
					} else if (t.estado == EstadoPagina.DISCO) {
						w = hw.disco.swap[t.slot][d];
						loc = "swap";
					} else if (t.estado == EstadoPagina.NAO_CARREGADA && prog != null && l < prog.length) {
						w = prog[l];
						loc = "prog";
					} else {
						continue;
					}
					System.out.print("  " + l + " (" + loc + "):  ");
					dump(w);
				}
			}
		}
	}

	// pedido de frame aguardando (todos os frames em trânsito/fixos)
	public class PedidoFrame {
		public PCB p;
		public int pagina;

		public PedidoFrame(PCB _p, int _pagina) {
			p = _p;
			pagina = _pagina;
		}
	}

	// ------------------- S O
	// ----------------------------------
	public class SO {
		public HW hw;
		public InterruptHandling ih;
		public SysCallHandling sc;
		public Utilities utils;
		public GM gm;
		public GD gd;
		public GP gp;

		public PCB running = null;                                  // processo rodando
		public LinkedList<PCB> ready = new LinkedList<>();          // Fila Prontos
		public LinkedList<PCB> bloqueadosConsole = new LinkedList<>(); // Fila Bloqueados console
		public LinkedList<PCB> bloqueadosVM = new LinkedList<>();   // Fila Bloqueados VM
		public LinkedList<PedidoFrame> pedidosFrame = new LinkedList<>(); // aguardando frame disponível

		public final ReentrantLock lock = new ReentrantLock(true);    // exclusão mútua das estruturas do SO
		public final Condition fimProcesso = lock.newCondition();      // sinaliza término de processo
		public final Semaphore semaSch = new Semaphore(0);             // libera o escalonador
		public final Semaphore semaCPU = new Semaphore(0);             // libera a CPU

		public volatile boolean modoContinuo = true;   // escalonamento contínuo (T2: sistema sempre operante)
		public volatile boolean log = true;            // mostra atividades de escalonamento, IO e VM

		public SO(HW _hw, int tamMem, int tamPg) {
			hw = _hw;
			ih = new InterruptHandling(hw); // rotinas de tratamento de int
			sc = new SysCallHandling(hw); // chamadas de sistema
			hw.cpu.setAddressOfHandlers(ih, sc);
			utils = new Utilities(hw);
			gm = new GM(tamMem, tamPg);
			gd = new GD(hw.disco.swap.length);
			gp = new GP();
		}

		// tira o processo rodando da CPU (contexto já salvo) e libera o escalonador
		private void tiraDaCPU() {
			running = null;
			hw.cpu.ativa = false;
			semaSch.release();
		}

		private void bloqueiaRunning(String motivo, LinkedList<PCB> fila) {
			PCB p = running;
			hw.cpu.saveContext(p);       // salva estado do processo
			p.estado = "bloqueado";
			p.motivoBloqueio = motivo;
			fila.addLast(p);
			tiraDaCPU();                 // escalona outro
		}

		private void desbloqueia(PCB p, LinkedList<PCB> fila) {
			fila.remove(p);
			p.estado = "pronto";
			p.motivoBloqueio = "";
			ready.addLast(p);
			if (running == null) {
				semaSch.release();       // CPU ociosa: libera escalonador
			}
		}

		// Rot Trat TIMER: salva estado do processo rodando, coloca na fila de prontos, libera escalonador
		public void rotTimer() {
			PCB p = running;
			hw.cpu.saveContext(p);
			p.estado = "pronto";
			ready.addLast(p);
			if (log) System.out.println("  [timer] processo " + p.id + " (" + p.nome + ") sai da CPU, pc=" + p.pc);
			tiraDaCPU();
		}

		// Rot Tratamento (STOP, overflow, acesso indevido, rm): finaliza processo, libera escalonador
		public void finalizaRunning() {
			PCB p = running;
			hw.cpu.saveContext(p);
			System.out.println("  [fim] processo " + p.id + " (" + p.nome + ") finalizado, paginas " + p.mapa()
					+ ", page faults: " + p.pageFaults + " (resultado visivel com dumpM)");
			running = null;
			hw.cpu.ativa = false;
			gp.libera(p);
			semaSch.release();
		}

		// ---------------------------------------------------------------- T2a: IO concorrente
		// Chamada IO: salva estado, bloqueia processo, empacota pedido para o console,
		// libera escalonador. O frame do endereço fica fixo (não pode ser vitimado) até o fim do DMA.
		public void chamadaIO(PedidoConsole ped) {
			gm.fixoIO[ped.endFisico / gm.tamPg]++;
			if (log) System.out.println("  [IO] processo " + ped.p.id + " bloqueado: pedido de "
					+ (ped.tipo == 1 ? "IN" : "OUT") + " no end " + ped.endLogico + " enviado ao console");
			bloqueiaRunning("IO", bloqueadosConsole);
			hw.console.filaPedidos.add(ped);       // produtor
			atendePedidosFrame();
		}

		// Rot Trat Ret IO: passa processo de bloqueado para pronto; o processo
		// interrompido (se houver) continua sua fatia de tempo.
		public void rotRetIO(PedidoConsole ped) {
			if (ped.p.terminado) return;          // frames do processo já foram liberados
			int f = ped.endFisico / gm.tamPg;
			if (gm.fixoIO[f] > 0) gm.fixoIO[f]--;
			if (log) System.out.println("  [int IO] processo " + ped.p.id + " terminou IO: bloqueado -> pronto");
			desbloqueia(ped.p, bloqueadosConsole);
			atendePedidosFrame();
		}

		// ---------------------------------------------------------------- T2b: memória virtual
		// Rot Page Fault: processo bloqueia, pede frame ao GM e encomenda leitura da página
		public void rotPageFault(int pagina) {
			PCB p = running;
			p.pageFaults++;
			if (log) System.out.println("  [page fault] processo " + p.id + " pagina " + pagina
					+ " (pc=" + hw.cpu.pc + ") -> bloqueado, frames " + p.mapa());
			bloqueiaRunning("VM", bloqueadosVM);   // pc não avança: a instrução é reexecutada
			pedeFrame(p, pagina);
			atendePedidosFrame();
		}

		// Pede quadro ao gerente de memória: frame livre, ou vitima uma página (FIFO)
		private void pedeFrame(PCB p, int pagina) {
			int f = gm.alocaFrame(p, pagina);
			if (f >= 0) {
				gm.emTransito[f] = true;
				if (log) System.out.println("  [GM] frame livre " + f + " para pagina " + pagina + " do processo " + p.id);
				carregaPagina(p, pagina, f);
				return;
			}
			boolean impasse = running == null && ready.isEmpty() && !gm.algumEmTransito();
			f = gm.escolheVitima(p, impasse);
			if (f < 0) {                                      // todos em trânsito / fixos: espera
				if (log) System.out.println("  [GM] sem frame disponivel: processo " + p.id + " aguarda frame");
				pedidosFrame.add(new PedidoFrame(p, pagina));
				return;
			}
			PCB vit = gm.dono[f];
			int pv = gm.paginaDe[f];
			EntradaTP tv = vit.tabelaPaginas[pv];
			if (tv.slot < 0) tv.slot = gd.aloca();            // espaço no swap para a cópia
			if (tv.slot < 0) {
				System.out.println("  [GD] swap cheio! processo " + p.id + " sera finalizado");
				gm.paginaPresente(f);                         // vítima continua onde estava
				gp.libera(p);
				return;
			}
			tv.estado = EstadoPagina.DISCO;                   // vítima sai da memória (próximo acesso: page fault)
			tv.frame = -1;
			gm.reserva(f, p, pagina);
			PedidoDisco ped = new PedidoDisco(PedidoDisco.SALVA, p, pagina, f, tv.slot);
			ped.vitima = vit;
			ped.paginaVitima = pv;
			if (log) System.out.println("  [GM] sem frame livre: vitima pagina " + pv + " do processo " + vit.id
					+ " (frame " + f + ") -> salvar no swap slot " + tv.slot);
			hw.disco.filaPedidos.add(ped);
		}

		// [**] adiciona mapeamento página/frame e encaminha pedido de carga ao disco
		private void carregaPagina(PCB p, int pagina, int f) {
			EntradaTP t = p.tabelaPaginas[pagina];
			int origem = t.estado == EstadoPagina.DISCO ? t.slot : -1;
			t.estado = EstadoPagina.EM_CARGA;
			t.frame = f;
			if (log) System.out.println("  [VM] pedido ao disco: pagina " + pagina + " do processo " + p.id
					+ (origem >= 0 ? " (do swap slot " + origem + ")" : " (do programa)") + " -> frame " + f);
			hw.disco.filaPedidos.add(new PedidoDisco(PedidoDisco.CARREGA, p, pagina, f, origem));
		}

		// Rot Trat Ret IO-VM (fim de salvamento): frame liberado passa ao processo demandante
		public void rotPaginaSalva(PedidoDisco ped) {
			if (log) System.out.println("  [int IO-VM] pagina " + ped.paginaVitima + " do processo " + ped.vitima.id
					+ " salva no swap slot " + ped.slot + "; frame " + ped.frame + " liberado");
			if (ped.p.terminado) {
				gm.liberaFrame(ped.frame);
				atendePedidosFrame();
				return;
			}
			carregaPagina(ped.p, ped.pagina, ped.frame);      // continua em [**]
		}

		// Rot Trat Ret IO-VM (fim de carga): processo bloqueado -> pronto (fim do page fault)
		public void rotPaginaCarregada(PedidoDisco ped) {
			if (ped.p.terminado) {
				gm.liberaFrame(ped.frame);
			} else {
				EntradaTP t = ped.p.tabelaPaginas[ped.pagina];
				t.estado = EstadoPagina.MEMORIA;
				t.frame = ped.frame;
				gm.paginaPresente(ped.frame);
				if (log) System.out.println("  [int IO-VM] pagina " + ped.pagina + " do processo " + ped.p.id
						+ " carregada no frame " + ped.frame + ": bloqueado -> pronto, frames " + ped.p.mapa());
				desbloqueia(ped.p, bloqueadosVM);
			}
			atendePedidosFrame();
		}

		// tenta atender processos que aguardam frame
		public void atendePedidosFrame() {
			int n = pedidosFrame.size();
			for (int i = 0; i < n && !pedidosFrame.isEmpty(); i++) {
				PedidoFrame pf = pedidosFrame.removeFirst();
				pedeFrame(pf.p, pf.pagina);                    // se ainda não houver, volta para a fila
			}
		}

		// escolhe o primeiro processo da fila de prontos que pode executar
		private PCB escolhe() {
			for (PCB p : ready) {
				if (modoContinuo || p.liberado) {
					return p;
				}
			}
			return null;
		}

		// Thread Escalonador
		public void loopEscalonador() {
			while (true) {
				semaSch.acquireUninterruptibly();              // aguarda bloqueado (semaSch.wait)
				lock.lock();
				try {
					if (running == null) {
						PCB p = escolhe();
						if (p != null) {
							ready.remove(p);
							running = p;
							p.estado = "rodando";
							hw.cpu.setContext(p.pc, p.reg, p.tabelaPaginas);  // restaura estado na CPU
							if (log) System.out.println("  [escalonador] processo " + p.id + " (" + p.nome + ") -> CPU, pc=" + p.pc);
							semaCPU.release();                 // semaCPU.notify
						}
					}
				} finally {
					lock.unlock();
				}
			}
		}
	}
	// -------------------------------------------------------------------------------------------------------
	// ------------------- S I S T E M A
	// --------------------------------------------------------------------

	public HW hw;
	public SO so;
	public Programs progs;

	public Sistema(int tamMem, int tamPg, int delta) {
		hw = new HW(tamMem, tamPg, delta);    // memoria do HW tem tamMem palavras
		so = new SO(hw, tamMem, tamPg);
		hw.cpu.setUtilities(so.utils); // permite cpu fazer dump de memoria ao avancar
		progs = new Programs();
	}

	// aguarda (no shell) até os processos ids terminarem, ou até algum pedir entrada
	private void aguarda(Set<Integer> ids) {
		so.lock.lock();
		try {
			while (true) {
				boolean vivo = false;
				for (int id : ids) {
					if (so.gp.busca(id) != null) vivo = true;
				}
				if (!vivo || hw.console.aguardandoIN != null) return;
				so.fimProcesso.await(50, TimeUnit.MILLISECONDS);
			}
		} catch (InterruptedException e) {
		} finally {
			so.lock.unlock();
		}
	}

	private void ajuda() {
		System.out.println("Comandos:");
		System.out.println("  new <programa>     cria processo (programas: " + progs.nomes() + ")");
		System.out.println("  rm <id>            remove processo");
		System.out.println("  ps                 lista processos, estados e frames de cada um");
		System.out.println("  dump <id>          PCB, tabela de paginas e memoria (logica) do processo");
		System.out.println("  dumpM <ini> <fim>  memoria fisica entre ini e fim");
		System.out.println("  frames             mapa de frames (dono e pagina de cada frame)");
		System.out.println("  swap               ocupacao da area de swap do disco");
		System.out.println("  <inteiro>          responde a um pedido de IN de processo");
		System.out.println("  exec <id>          executa o processo id (modo nao continuo)");
		System.out.println("  execAll            executa todos os processos (modo nao continuo)");
		System.out.println("  autoOn / autoOff   liga/desliga escalonamento continuo (padrao: ligado)");
		System.out.println("  traceOn / traceOff liga/desliga trace de instrucoes");
		System.out.println("  logOn / logOff     liga/desliga log de escalonamento, IO e VM");
		System.out.println("  delay <ms>         atraso da CPU por instrucao (0 = sem atraso)");
		System.out.println("  delayIO <ms>       latencia do console");
		System.out.println("  delayDisco <ms>    latencia do disco (padrao 20)");
		System.out.println("  exit               sai do sistema");
	}

	private void mostraProcessos() {
		if (so.gp.processos.isEmpty()) System.out.println("nenhum processo");
		for (PCB p : so.gp.processos) {
			if (p == so.running) hw.cpu.saveContext(p);
			System.out.println("  id " + p.id + "  " + p.nome + "  " + p.estadoCompleto() + "  pc=" + p.pc
					+ "  pf=" + p.pageFaults + "  " + p.mapa());
		}
		System.out.println("  prontos: " + ids(so.ready) + "   bloqueados IO: " + ids(so.bloqueadosConsole)
				+ "   bloqueados VM: " + ids(so.bloqueadosVM));
	}

	private String ids(List<PCB> l) {
		StringJoiner sj = new StringJoiner(",", "[", "]");
		for (PCB p : l) sj.add(String.valueOf(p.id));
		return sj.toString();
	}

	private void mostraFrames() {
		StringBuilder sb = new StringBuilder();
		for (int f = 0; f < so.gm.nroFrames; f++) {
			String s;
			if (so.gm.livre[f]) s = ".";
			else s = "P" + so.gm.dono[f].id + ":p" + so.gm.paginaDe[f]
					+ (so.gm.emTransito[f] ? "*" : "") + (so.gm.fixoIO[f] > 0 ? "!" : "");
			sb.append(String.format("%4d %-9s", f, s));
			if ((f + 1) % 8 == 0) sb.append('\n');
		}
		System.out.println(sb.toString().replaceAll("\\s+$", ""));
		System.out.println("frames livres: " + so.gm.framesLivres() + " de " + so.gm.nroFrames
				+ "   (Pid:pagina, * em transito com o disco, ! fixo por IO, . livre)");
		System.out.println("ordem FIFO (proxima vitima primeiro): " + so.gm.fifo);
	}

	// Thread Shell: le comandos do usuario e submete ao SO (ou manda para o console)
	public void run() {
		Thread tEsc = new Thread(so::loopEscalonador, "Escalonador");
		Thread tCPU = new Thread(hw.cpu::loopCPU, "CPU");
		Thread tCon = new Thread(hw.console::loop, "Console");
		Thread tDis = new Thread(hw.disco::loop, "IO-VM");
		for (Thread t : new Thread[] { tEsc, tCPU, tCon, tDis }) {
			t.setDaemon(true);
			t.start();
		}

		System.out.println("SO iniciado: tamMem=" + hw.mem.pos.length + "  tamPg=" + so.gm.tamPg
				+ "  frames=" + so.gm.nroFrames + "  delta=" + hw.cpu.delta + "  swap=" + so.gd.total() + " slots");
		ajuda();

		Scanner in = new Scanner(System.in);
		while (true) {
			System.out.print("> ");
			if (!in.hasNextLine()) break;
			String linha = in.nextLine().trim();
			if (linha.isEmpty()) continue;

			// resposta a um pedido de IN de processo: manda para o console
			if (linha.matches("-?\\d+")) {
				if (hw.console.aguardandoIN != null) hw.console.entradas.offer(Integer.parseInt(linha));
				else System.out.println("nenhum processo aguardando IN");
				continue;
			}

			String[] t = linha.split("[\\s,<>]+");
			if (t.length > 0 && t[0].isEmpty()) t = Arrays.copyOfRange(t, 1, t.length);
			String cmd = t[0];
			try {
				switch (cmd) {
					case "new": {
						int id;
						so.lock.lock();
						try { id = so.gp.criaProcesso(t[1]); } finally { so.lock.unlock(); }
						if (id > 0) System.out.println("processo criado: id " + id);
						break;
					}
					case "rm": {
						boolean ok, naCPU;
						int id = Integer.parseInt(t[1]);
						so.lock.lock();
						try {
							naCPU = so.running != null && so.running.id == id;
							ok = so.gp.desalocaProcesso(id);
						} finally { so.lock.unlock(); }
						System.out.println(!ok ? "processo inexistente"
								: naCPU ? "processo na CPU: sera removido na proxima instrucao (intProcessoRemovido)"
								: "processo removido");
						break;
					}
					case "ps": {
						so.lock.lock();
						try { mostraProcessos(); } finally { so.lock.unlock(); }
						break;
					}
					case "dump": {
						so.lock.lock();
						try {
							PCB p = so.gp.busca(Integer.parseInt(t[1]));
							if (p == null) {
								System.out.println("processo inexistente");
							} else {
								if (p == so.running) hw.cpu.saveContext(p);  // mostra contexto atual
								so.utils.dumpProcesso(p);
							}
						} finally { so.lock.unlock(); }
						break;
					}
					case "dumpM": {
						so.lock.lock();
						try { so.utils.dump(Integer.parseInt(t[1]), Integer.parseInt(t[2])); } finally { so.lock.unlock(); }
						break;
					}
					case "frames": {
						so.lock.lock();
						try { mostraFrames(); } finally { so.lock.unlock(); }
						break;
					}
					case "swap": {
						so.lock.lock();
						try {
							System.out.println("swap: " + (so.gd.total() - so.gd.livres()) + " de " + so.gd.total() + " slots ocupados");
							for (PCB p : so.gp.processos) {
								for (int pg = 0; pg < p.tabelaPaginas.length; pg++) {
									EntradaTP e = p.tabelaPaginas[pg];
									if (e.slot >= 0) System.out.println("  slot " + e.slot + ": processo " + p.id
											+ " pagina " + pg + (e.estado == EstadoPagina.DISCO ? " (copia atual)" : " (pagina esta em memoria)"));
								}
							}
						} finally { so.lock.unlock(); }
						break;
					}
					case "exec": {
						int id = Integer.parseInt(t[1]);
						boolean existe;
						so.lock.lock();
						try {
							PCB p = so.gp.busca(id);
							existe = p != null;
							if (existe) { p.liberado = true; so.semaSch.release(); }
						} finally { so.lock.unlock(); }
						if (!existe) { System.out.println("erro: processo inexistente"); break; }
						aguarda(Collections.singleton(id));
						break;
					}
					case "execAll": {
						Set<Integer> ids = new HashSet<>();
						so.lock.lock();
						try {
							for (PCB p : so.gp.processos) { p.liberado = true; ids.add(p.id); }
							so.semaSch.release();
						} finally { so.lock.unlock(); }
						if (ids.isEmpty()) { System.out.println("nenhum processo"); break; }
						aguarda(ids);
						break;
					}
					case "autoOn":
						so.modoContinuo = true;
						so.semaSch.release();
						System.out.println("escalonamento continuo ligado");
						break;
					case "autoOff":
						so.modoContinuo = false;
						System.out.println("escalonamento continuo desligado");
						break;
					case "traceOn":    hw.cpu.debug = true;  break;
					case "traceOff":   hw.cpu.debug = false; break;
					case "logOn":      so.log = true;        break;
					case "logOff":     so.log = false;       break;
					case "delay":      hw.cpu.atraso = Integer.parseInt(t[1]); break;
					case "delayIO":    hw.console.atraso = Integer.parseInt(t[1]); break;
					case "delayDisco": hw.disco.atraso = Integer.parseInt(t[1]); break;
					case "help":       ajuda();              break;
					case "exit":       System.exit(0);
					default:
						System.out.println("comando desconhecido (help para ajuda)");
				}
			} catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
				System.out.println("parametros invalidos (help para ajuda)");
			}
		}
		System.exit(0);
	}
	// ------------------- S I S T E M A - fim
	// --------------------------------------------------------------
	// -------------------------------------------------------------------------------------------------------

	// -------------------------------------------------------------------------------------------------------
	// ------------------- instancia e testa sistema
	public static void main(String args[]) {
		int tamMem = args.length > 0 ? Integer.parseInt(args[0]) : 1024;
		int tamPg  = args.length > 1 ? Integer.parseInt(args[1]) : 8;
		int delta  = args.length > 2 ? Integer.parseInt(args[2]) : 10;
		if (tamPg <= 0 || tamMem <= 0 || tamMem % tamPg != 0 || delta <= 0) {
			System.out.println("tamMem deve ser multiplo de tamPg, e todos positivos");
			return;
		}
		Sistema s = new Sistema(tamMem, tamPg, delta);
		s.run();
	}


	// -------------------------------------------------------------------------------------------------------
	// -------------------------------------------------------------------------------------------------------
	// -------------------------------------------------------------------------------------------------------
	// --------------- P R O G R A M A S - não fazem parte do sistema
	// esta classe representa programas armazenados (como se estivessem em disco)
	// que podem ser carregados para a memória (load faz isto)

	public class Program {
		public String name;
		public Word[] image;

		public Program(String n, Word[] i) {
			name = n;
			image = i;
		}
	}

	public class Programs {

		public Word[] retrieveProgram(String pname) {
			for (Program p : progs) {
				if (p != null && p.name.equals(pname))   // equals: nome vem digitado pelo usuário
					return p.image;
			}
			return null;
		}

		public String nomes() {
			StringJoiner sj = new StringJoiner(", ");
			for (Program p : progs) sj.add(p.name);
			return sj.toString();
		}

		public Program[] progs = {
				new Program("fatorial",
						new Word[] {
								// este fatorial so aceita valores positivos. nao pode ser zero
								// linha coment
								new Word(Opcode.LDI, 0, -1, 7), // 0 r0 é valor a calcular fatorial
								new Word(Opcode.LDI, 1, -1, 1), // 1 r1 é 1 para multiplicar (por r0)
								new Word(Opcode.LDI, 6, -1, 1), // 2 r6 é 1 o decremento
								new Word(Opcode.LDI, 7, -1, 8), // 3 r7 tem posicao 8 para fim do programa
								new Word(Opcode.JMPIE, 7, 0, 0), // 4 se r0=0 pula para r7(=8)
								new Word(Opcode.MULT, 1, 0, -1), // 5 r1 = r1 * r0 (r1 acumula o produto por cada termo)
								new Word(Opcode.SUB, 0, 6, -1), // 6 r0 = r0 - r6 (r6=1) decrementa r0 para proximo
																// termo
								new Word(Opcode.JMP, -1, -1, 4), // 7 vai p posicao 4
								new Word(Opcode.STD, 1, -1, 10), // 8 coloca valor de r1 na posição 10
								new Word(Opcode.STOP, -1, -1, -1), // 9 stop
								new Word(Opcode.DATA, -1, -1, -1) // 10 ao final o valor está na posição 10 da memória
						}),

				new Program("fatorialV2",
						new Word[] {
								new Word(Opcode.LDI, 0, -1, 5), // numero para colocar na memoria, ou pode ser lido
								new Word(Opcode.STD, 0, -1, 19),
								new Word(Opcode.LDD, 0, -1, 19),
								new Word(Opcode.LDI, 1, -1, -1),
								new Word(Opcode.LDI, 2, -1, 13), // SALVAR POS STOP
								new Word(Opcode.JMPIL, 2, 0, -1), // caso negativo pula pro STD
								new Word(Opcode.LDI, 1, -1, 1),
								new Word(Opcode.LDI, 6, -1, 1),
								new Word(Opcode.LDI, 7, -1, 13),
								new Word(Opcode.JMPIE, 7, 0, 0), // POS 9 pula para STD (Stop-1)
								new Word(Opcode.MULT, 1, 0, -1),
								new Word(Opcode.SUB, 0, 6, -1),
								new Word(Opcode.JMP, -1, -1, 9), // pula para o JMPIE
								new Word(Opcode.STD, 1, -1, 18),
								new Word(Opcode.LDI, 8, -1, 2), // escrita
								new Word(Opcode.LDI, 9, -1, 18), // endereco com valor a escrever
								new Word(Opcode.SYSCALL, -1, -1, -1),
								new Word(Opcode.STOP, -1, -1, -1), // POS 17
								new Word(Opcode.DATA, -1, -1, -1), // POS 18
								new Word(Opcode.DATA, -1, -1, -1) } // POS 19
				),

				new Program("progMinimo",
						new Word[] {
								new Word(Opcode.LDI, 0, -1, 999),
								new Word(Opcode.STD, 0, -1, 8),
								new Word(Opcode.STD, 0, -1, 9),
								new Word(Opcode.STD, 0, -1, 10),
								new Word(Opcode.STD, 0, -1, 11),
								new Word(Opcode.STD, 0, -1, 12),
								new Word(Opcode.STOP, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1), // 7
								new Word(Opcode.DATA, -1, -1, -1), // 8
								new Word(Opcode.DATA, -1, -1, -1), // 9
								new Word(Opcode.DATA, -1, -1, -1), // 10
								new Word(Opcode.DATA, -1, -1, -1), // 11
								new Word(Opcode.DATA, -1, -1, -1), // 12
								new Word(Opcode.DATA, -1, -1, -1) // 13
						}),

				new Program("fibonacci10",
						new Word[] { // mesmo que prog exemplo, so que usa r0 no lugar de r8
								new Word(Opcode.LDI, 1, -1, 0),
								new Word(Opcode.STD, 1, -1, 20),
								new Word(Opcode.LDI, 2, -1, 1),
								new Word(Opcode.STD, 2, -1, 21),
								new Word(Opcode.LDI, 0, -1, 22),
								new Word(Opcode.LDI, 6, -1, 6),
								new Word(Opcode.LDI, 7, -1, 31),
								new Word(Opcode.LDI, 3, -1, 0),
								new Word(Opcode.ADD, 3, 1, -1),
								new Word(Opcode.LDI, 1, -1, 0),
								new Word(Opcode.ADD, 1, 2, -1),
								new Word(Opcode.ADD, 2, 3, -1),
								new Word(Opcode.STX, 0, 2, -1),
								new Word(Opcode.ADDI, 0, -1, 1),
								new Word(Opcode.SUB, 7, 0, -1),
								new Word(Opcode.JMPIG, 6, 7, -1),
								new Word(Opcode.STOP, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1), // POS 20
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1) // ate aqui - serie de fibonacci ficara armazenada
						}),

				new Program("fibonacci10v2",
						new Word[] { // mesmo que prog exemplo, so que usa r0 no lugar de r8
								new Word(Opcode.LDI, 1, -1, 0),
								new Word(Opcode.STD, 1, -1, 20),
								new Word(Opcode.LDI, 2, -1, 1),
								new Word(Opcode.STD, 2, -1, 21),
								new Word(Opcode.LDI, 0, -1, 22),
								new Word(Opcode.LDI, 6, -1, 6),
								new Word(Opcode.LDI, 7, -1, 31),
								new Word(Opcode.MOVE, 3, 1, -1),
								new Word(Opcode.MOVE, 1, 2, -1),
								new Word(Opcode.ADD, 2, 3, -1),
								new Word(Opcode.STX, 0, 2, -1),
								new Word(Opcode.ADDI, 0, -1, 1),
								new Word(Opcode.SUB, 7, 0, -1),
								new Word(Opcode.JMPIG, 6, 7, -1),
								new Word(Opcode.STOP, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1), // POS 20
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1) // ate aqui - serie de fibonacci ficara armazenada
						}),
				new Program("fibonacciREAD",
						new Word[] {
								// mesmo que prog exemplo, so que usa r0 no lugar de r8
								new Word(Opcode.LDI, 8, -1, 1), // leitura
								new Word(Opcode.LDI, 9, -1, 55), // endereco a guardar o tamanho da serie de fib a gerar
																	// - pode ser de 1 a 20
								new Word(Opcode.SYSCALL, -1, -1, -1),
								new Word(Opcode.LDD, 7, -1, 55),
								new Word(Opcode.LDI, 3, -1, 0),
								new Word(Opcode.ADD, 3, 7, -1),
								new Word(Opcode.LDI, 4, -1, 36), // posicao para qual ira pular (stop) *
								new Word(Opcode.LDI, 1, -1, -1), // caso negativo
								new Word(Opcode.STD, 1, -1, 41),
								new Word(Opcode.JMPIL, 4, 7, -1), // pula pra stop caso negativo *
								new Word(Opcode.JMPIE, 4, 7, -1), // pula pra stop caso 0
								new Word(Opcode.ADDI, 7, -1, 41), // fibonacci + posição do stop
								new Word(Opcode.LDI, 1, -1, 0),
								new Word(Opcode.STD, 1, -1, 41), // 25 posicao de memoria onde inicia a serie de
																	// fibonacci gerada
								new Word(Opcode.SUBI, 3, -1, 1), // se 1 pula pro stop
								new Word(Opcode.JMPIE, 4, 3, -1),
								new Word(Opcode.ADDI, 3, -1, 1),
								new Word(Opcode.LDI, 2, -1, 1),
								new Word(Opcode.STD, 2, -1, 42),
								new Word(Opcode.SUBI, 3, -1, 2), // se 2 pula pro stop
								new Word(Opcode.JMPIE, 4, 3, -1),
								new Word(Opcode.LDI, 0, -1, 43),
								new Word(Opcode.LDI, 6, -1, 25), // salva posição de retorno do loop
								new Word(Opcode.LDI, 5, -1, 0), // salva tamanho
								new Word(Opcode.ADD, 5, 7, -1),
								new Word(Opcode.LDI, 7, -1, 0), // zera (inicio do loop)
								new Word(Opcode.ADD, 7, 5, -1), // recarrega tamanho
								new Word(Opcode.LDI, 3, -1, 0),
								new Word(Opcode.ADD, 3, 1, -1),
								new Word(Opcode.LDI, 1, -1, 0),
								new Word(Opcode.ADD, 1, 2, -1),
								new Word(Opcode.ADD, 2, 3, -1),
								new Word(Opcode.STX, 0, 2, -1),
								new Word(Opcode.ADDI, 0, -1, 1),
								new Word(Opcode.SUB, 7, 0, -1),
								new Word(Opcode.JMPIG, 6, 7, -1), // volta para o inicio do loop
								new Word(Opcode.STOP, -1, -1, -1), // POS 36
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1), // POS 41
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1)
						}),
				new Program("PB",
						new Word[] {
								// dado um inteiro em alguma posição de memória,
								// se for negativo armazena -1 na saída; se for positivo responde o fatorial do
								// número na saída
								new Word(Opcode.LDI, 0, -1, 7), // numero para colocar na memoria
								new Word(Opcode.STD, 0, -1, 50),
								new Word(Opcode.LDD, 0, -1, 50),
								new Word(Opcode.LDI, 1, -1, -1),
								new Word(Opcode.LDI, 2, -1, 13), // SALVAR POS STOP
								new Word(Opcode.JMPIL, 2, 0, -1), // caso negativo pula pro STD
								new Word(Opcode.LDI, 1, -1, 1),
								new Word(Opcode.LDI, 6, -1, 1),
								new Word(Opcode.LDI, 7, -1, 13),
								new Word(Opcode.JMPIE, 7, 0, 0), // POS 9 pula pra STD (Stop-1)
								new Word(Opcode.MULT, 1, 0, -1),
								new Word(Opcode.SUB, 0, 6, -1),
								new Word(Opcode.JMP, -1, -1, 9), // pula para o JMPIE
								new Word(Opcode.STD, 1, -1, 15),
								new Word(Opcode.STOP, -1, -1, -1), // POS 14
								new Word(Opcode.DATA, -1, -1, -1) // POS 15
						}),
				new Program("PC",
						new Word[] {
								// Para um N definido (10 por exemplo)
								// o programa ordena um vetor de N números em alguma posição de memória;
								// ordena usando bubble sort
								// loop ate que não swap nada
								// passando pelos N valores
								// faz swap de vizinhos se da esquerda maior que da direita
								new Word(Opcode.LDI, 7, -1, 5), // TAMANHO DO BUBBLE SORT (N)
								new Word(Opcode.LDI, 6, -1, 5), // aux N
								new Word(Opcode.LDI, 5, -1, 46), // LOCAL DA MEMORIA
								new Word(Opcode.LDI, 4, -1, 47), // aux local memoria
								new Word(Opcode.LDI, 0, -1, 4), // colocando valores na memoria
								new Word(Opcode.STD, 0, -1, 46),
								new Word(Opcode.LDI, 0, -1, 3),
								new Word(Opcode.STD, 0, -1, 47),
								new Word(Opcode.LDI, 0, -1, 5),
								new Word(Opcode.STD, 0, -1, 48),
								new Word(Opcode.LDI, 0, -1, 1),
								new Word(Opcode.STD, 0, -1, 49),
								new Word(Opcode.LDI, 0, -1, 2),
								new Word(Opcode.STD, 0, -1, 50), // colocando valores na memoria até aqui - POS 13
								new Word(Opcode.LDI, 3, -1, 25), // Posicao para pulo CHAVE 1
								new Word(Opcode.STD, 3, -1, 99),
								new Word(Opcode.LDI, 3, -1, 22), // Posicao para pulo CHAVE 2
								new Word(Opcode.STD, 3, -1, 98),
								new Word(Opcode.LDI, 3, -1, 38), // Posicao para pulo CHAVE 3
								new Word(Opcode.STD, 3, -1, 97),
								new Word(Opcode.LDI, 3, -1, 25), // Posicao para pulo CHAVE 4 (não usada)
								new Word(Opcode.STD, 3, -1, 96),
								new Word(Opcode.LDI, 6, -1, 0), // r6 = r7 - 1 POS 22
								new Word(Opcode.ADD, 6, 7, -1),
								new Word(Opcode.SUBI, 6, -1, 1), // ate aqui
								new Word(Opcode.JMPIEM, -1, 6, 97), // CHAVE 3 para pular quando r7 for 1 e r6 0 para
																	// interomper o loop de vez do programa
								new Word(Opcode.LDX, 0, 5, -1), // r0 e ra pegando valores das posições da memoria POS
																// 26
								new Word(Opcode.LDX, 1, 4, -1),
								new Word(Opcode.LDI, 2, -1, 0),
								new Word(Opcode.ADD, 2, 0, -1),
								new Word(Opcode.SUB, 2, 1, -1),
								new Word(Opcode.ADDI, 4, -1, 1),
								new Word(Opcode.SUBI, 6, -1, 1),
								new Word(Opcode.JMPILM, -1, 2, 99), // LOOP chave 1 caso neg procura prox
								new Word(Opcode.STX, 5, 1, -1),
								new Word(Opcode.SUBI, 4, -1, 1),
								new Word(Opcode.STX, 4, 0, -1),
								new Word(Opcode.ADDI, 4, -1, 1),
								new Word(Opcode.JMPIGM, -1, 6, 99), // LOOP chave 1 POS 38
								new Word(Opcode.ADDI, 5, -1, 1),
								new Word(Opcode.SUBI, 7, -1, 1),
								new Word(Opcode.LDI, 4, -1, 0), // r4 = r5 + 1 POS 41
								new Word(Opcode.ADD, 4, 5, -1),
								new Word(Opcode.ADDI, 4, -1, 1), // ate aqui
								new Word(Opcode.JMPIGM, -1, 7, 98), // LOOP chave 2
								new Word(Opcode.STOP, -1, -1, -1), // POS 45
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1)
						})
		};
	}
}
