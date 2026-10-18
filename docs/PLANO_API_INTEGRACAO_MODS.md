# Plano de implementação — API de integração do Lazarus NPCs

**Estado:** planejamento, sem implementação iniciada  
**Projetos:** Lazarus NPCs e Lazarus Events  
**Base verificada:** Lazarus NPCs 1.4.2; Lazarus Events 0.3.5; Minecraft 1.20.1, Forge 47.x, Java 17

## 1. Objetivo e limites

Permitir que outros mods do Project Lazarus usem os NPCs humanos já existentes para criar encontros com comportamento, falas, escolhas, tarefas, recompensas e consequências persistentes. O mod integrador define o **contexto e o resultado narrativo**; o Lazarus NPCs executa e valida a **IA, interação, diálogo, diário, tarefas e reputação** no servidor.

A API será um pacote público versionado **dentro do JAR `lazarusnpcs`**. Não criar um terceiro mod nem alterar o JAR do Apocalypse Now. A primeira integração consumidora será o Lazarus Events. Os quatro encontros atuais devem continuar funcionando quando a API for introduzida; novos comportamentos serão adicionados a eles apenas quando fizerem sentido.

### Requisitos funcionais

1. Consultar identidade, papel e facção de um NPC sem ler NBT interno.
2. Atribuir e retirar ordens temporárias de cenário: guardar área, defender aliado, seguir jogador, esperar e recuar.
3. Registrar novos diálogos contextuais, falas e opções de resposta com consequências.
4. Registrar novos tipos de tarefa com objetivo, progresso, prazo, conclusão, falha e recompensa.
5. Mostrar diálogos e tarefas de evento nas interfaces atuais do NPC e no diário do jogador.
6. Ajustar reputação por uma ação identificada, usando as regras centrais do Lazarus NPCs.
7. Informar ao mod integrador acontecimentos relevantes: NPC salvo, morto, atacado, tarefa concluída ou fracassada, encontro cancelado.
8. Persistir o vínculo com o encontro e recuperar o estado após sair e entrar no mundo, descarregar chunks e reiniciar o servidor.

### Fora da primeira versão

- Novas entidades, facções ou sistema genérico de criação de IA para qualquer mob.
- Execução de comandos arbitrários definidos em JSON.
- Registro de telas próprias por outros mods dentro do Lazarus NPCs.
- Alteração automática dos encontros já gerados em mundos existentes.

## 2. Situação atual e mudanças necessárias

| Código atual | Limitação para integração | Mudança planejada |
|---|---|---|
| `NpcIdentity` e `NpcReputation` são internos ao pacote. | Outros mods precisariam duplicar regras ou acessar NBT. | Expor consultas e uma operação controlada de reputação por API pública. |
| `HumanTactics` instala uma ordem prioritária para alguns NPCs; contratos e rendição também comandam movimento. | Uma nova ordem pode brigar com a IA normal. | Resolver ordens de cenário no mesmo coordenador, com precedência explícita. |
| `NpcDialogue` escolhe falas em listas fixas e envia linhas ao chat. | Eventos não podem oferecer falas ou escolhas próprias. | Registro de diálogos por ID, seleção contextual e uma interface de escolhas. |
| `NpcTasks` reconhece tipos fixos por `switch` e guarda `LazarusNpcsTasks.Active`. | Nova tarefa substituiria ou bloquearia a missão comum. | Manter a tarefa comum; criar armazenamento e página **Encontros** separados, com tarefas identificadas por `ResourceLocation`. |
| `NpcNetwork.View` envia resumos de tamanho limitado e `NpcClient` monta botões fixos. | Não transporta opções de diálogo nem várias tarefas. | Introduzir mensagens paginadas/estruturadas, limites de tamanho e ações validadas no servidor. |
| Lazarus Events cria entidades do Apocalypse Now e as marca com ID do encontro. | Não atribui comportamento ou interação especial. | Após spawn, vincular NPC e cenário pela API; liberar vínculo ao cancelar ou encerrar. |

## 3. Contrato público proposto

Criar `com.lazarus.npcs.api` com tipos pequenos e documentados. As assinaturas abaixo são **contratos de planejamento**, não código já existente:

```java
NpcApi.identity(LivingEntity npc) -> Optional<NpcIdentityView>
NpcApi.assign(Mob npc, ScenarioAssignment assignment) -> AssignmentResult
NpcApi.clearAssignment(Mob npc, ResourceLocation owner, UUID encounterId) -> boolean
NpcApi.adjustReputation(ServerPlayer player, String faction, int delta,
                        ResourceLocation reason) -> ReputationResult
NpcApi.registerDialogue(DialogueDefinition definition)
NpcApi.registerTask(TaskDefinition definition)
NpcApi.offerTask(ServerPlayer player, LivingEntity giver,
                 ResourceLocation taskId, UUID encounterId) -> OfferResult
NpcApi.progressTask(ServerPlayer player, UUID instanceId,
                    ResourceLocation progressKey, int amount) -> ProgressResult
NpcApi.finishTask(ServerPlayer player, UUID instanceId,
                  ResourceLocation outcome) -> FinishResult
```

- Usar `ResourceLocation` com namespace do mod de origem, por exemplo `lazarusevents:rescue_survivor`. IDs duplicados falham claramente no registro.
- Métodos que mudam mundo ou jogador só executam no **servidor, na thread do servidor**. Rejeitar entidade morta, mundo incorreto, encontro expirado e valores fora de limites definidos.
- Retornar um resultado tipado com motivo de falha (`UNSUPPORTED_NPC`, `BUSY`, `EXPIRED`, `INVALID_CONTEXT`, `MISSING_DEFINITION`) em vez de exceções para rejeições normais.
- A API não expõe `CompoundTag` interno, `OrderGoal`, `NpcMerchant`, pacotes de rede ou instâncias mutáveis de tarefas.
- Registrar definições no carregamento apropriado do mod; não criar registros repetidos a cada entrada no mundo. Documentar thread, fase de registro e compatibilidade binária de cada método público.

### 3.1 Identidade e reputação

`NpcIdentityView` fornece `entityId`, `uuid`, `name`, `role`, `faction` e se o NPC aceita interação. O nome usa o mecanismo persistente atual. A reputação só pode ser alterada por operação identificada e validada no servidor, com limite por resultado para evitar concessões duplicadas. Registrar `reason` e `encounterId` no histórico técnico para depuração; não mudar a fórmula de reputação das trocas existentes.

### 3.2 Comportamento de cenário

`ScenarioAssignment` contém `ownerModId`, `encounterId`, `order`, `anchor` (dimensão e posição), `targetUuid` opcional, raio, prazo e prioridade **limitada a uma faixa de cenários**. Ordens iniciais: `GUARD_AREA`, `DEFEND_ALLY`, `FOLLOW_PLAYER`, `WAIT` e `RETREAT`.

Precedência: morte/remoção e rendição > combate imediato/defesa própria > contrato válido do jogador > ordem de cenário > patrulha/rotina. Uma ordem de evento nunca toma um NPC contratado de outro jogador, nem obriga um NPC hostil a negociar. O coordenador de ordens reutiliza a navegação existente e respeita `spike_barrier` e `barbed_wire`. Limitar atualização periódica e buscas de caminho; não adicionar um novo `Goal` a cada tick ou recarga.

O vínculo é persistido na entidade por ID do encontro e revalidado na entrada do chunk. `clearAssignment` remove apenas a ordem daquele proprietário. Prazo vencido, evento cancelado, entidade removida e mod integrador ausente devem produzir limpeza previsível. NPCs persistentes são exceção explícita, não padrão para todo encontro.

### 3.3 Diálogos, falas e escolhas

Uma `DialogueDefinition` define ID, gatilho (`GREETING`, `INTERACT`, `TASK_OFFER`, `TASK_PROGRESS`, `TASK_COMPLETE`, `THREAT`, `FAREWELL`), condições de cenário/facção/reputação, linhas traduzíveis e opções identificadas por ID. O provedor pode construir opções dinâmicas no servidor para um encontro específico. Falas estáticas podem residir em JSON de datapack e chaves de tradução; callbacks Java ficam reservados para condições e consequências dinâmicas.

Fluxo: jogador interage → servidor escolhe diálogo elegível → cliente exibe nome, fala e opções → jogador escolhe → servidor revalida jogador, NPC, distância, visibilidade, estado e disponibilidade → consequência ocorre **uma vez** → diálogo ou tela do NPC é atualizado. Se não houver diálogo de cenário elegível, permanece a saudação atual. A cor do nome continua seguindo a facção. Conservar cooldown de fala e limite de opções/linhas para evitar spam e pacotes excessivos.

Consequências permitidas por definição: avançar diálogo, oferecer/atualizar tarefa, atribuir ordem, alterar reputação por motivo e disparar evento de resultado. JSON não executa comando ou código arbitrário. Uma escolha que cobra itens usa operação transacional no servidor; se a verificação falhar, não consome nada nem avança estado.

### 3.4 Tarefas novas

`TaskDefinition` declara ID, título/descrição traduzíveis, categoria `ENCOUNTER`, tipo de objetivo, prazo, política de morte (`FAIL` ou `PERSIST`), condição de entrega, recompensa e callbacks de resultado. Objetivos iniciais: entregar itens, chegar a local, proteger NPC até um prazo, acompanhar NPC a um destino e eliminar infectados vinculados ao encontro. Objetivos adicionais entram por provedor Java, sem ampliar um `switch` central para cada missão nova.

`TaskInstance` guarda UUID próprio, definição, dono (UUID do jogador), encontro, NPC emissor, dimensão, posições necessárias, progresso, timestamps e estado (`OFFERED`, `ACTIVE`, `READY`, `COMPLETED`, `FAILED`, `EXPIRED`). Aceitação, progresso e recompensa são idempotentes: repetir pacote, recarregar mundo ou clicar duas vezes não duplica itens/reputação. Recompensas incluem itens, reputação e desbloqueios do encontro, sempre descritos na UI antes de aceitar. Inventário cheio usa política explícita (entrega segura no chão ao lado do jogador ou retirada posterior), sem perda silenciosa.

**Migração:** manter `LazarusNpcsTasks.Active` e seu comportamento atual. Criar armazenamento versionado separado para tarefas de evento; nenhuma tarefa existente é apagada ou convertida. Permitir inicialmente uma tarefa comum e **uma tarefa de encontro** simultâneas; novas ofertas de encontro mostram conflito e exigem abandono explícito da anterior. A aba **Encontros** do diário mostra objetivo, emissor, local, progresso, tempo, recompensa e estado. A aba atual de tarefas permanece acessível. Após morte, aplicar a política de cada definição; não copiar cegamente o slot antigo.

### 3.5 Eventos de resultado

Publicar eventos Forge do Lazarus NPCs para `NpcScenarioStarted`, `NpcScenarioEnded`, `NpcScenarioActorHurt/Died`, `NpcDialogueChoice`, `NpcTaskStateChanged` e `NpcReputationChanged`, com IDs, UUIDs e motivo. Emitir após alteração confirmada no servidor e documentar se o evento é observacional ou cancelável. O Lazarus Events consome esses eventos para registrar desfechos; não consulta NBT privado do NPC. Evitar callbacks em loop (`Events` reage → altera tarefa → dispara novo evento sem guarda).

## 4. Interface, rede e segurança

1. Adicionar uma página de diálogo com 2–4 escolhas legíveis, botão de retorno e nomes coloridos por facção. Manter as abas atuais **Trade**, **Services**, **Tasks** e **Guard**.
2. Adicionar seção **Encontros** ao diário, com paginação ou rolagem, quebra de texto e limite visual, preservando a página de reputação e a tarefa comum.
3. Enviar ao cliente apenas um modelo de exibição já validado pelo servidor: IDs, `Component`/chaves de tradução, estado e opções disponíveis. O cliente envia **ID da opção e ID da instância**, nunca recompensa, custo ou progresso fornecido por ele.
4. Criar novos pacotes com limites explícitos de bytes, comprimento de texto e quantidade de entradas; atualizar o protocolo de rede do mod de forma intencional. Clientes e servidores incompatíveis devem falhar com mensagem clara.
5. Revalidar no servidor a distância máxima, linha de visão quando aplicável, reputação, NPC vivo, dimensão, propriedade do encontro, prazo e estado. Bloquear repetição e corrida entre dois jogadores no mesmo NPC.
6. Definir comportamento quando o mod integrador é removido: uma tarefa cujo provedor não está registrado fica visível como indisponível, pode ser abandonada e nunca paga recompensa desconhecida; o servidor não deve falhar ao carregar o save.

## 5. Integração do Lazarus Events

O Events continua responsável por agendar encontros, escolher terreno, colocar template/loot e criar atores. Após cada spawn bem-sucedido, vincula o NPC ao `encounterId` e atribui comportamento pela API. A colocação só é considerada concluída depois que seus atores obrigatórios recebem atribuição; falha parcial deve limpar atores e vínculos. Ao cancelar/expirar, liberar ordens e tarefas conforme a política do evento. Não limpar construções ou loot já acessados pelo jogador.

**Primeiros cenários consumidores:**

| Evento | Ordem | Diálogo | Tarefa e resultado |
|---|---|---|---|
| **Pedido de socorro** | Ferido espera; depois segue o jogador. | Pedir ajuda, explicar necessidade, agradecer ou recusar. | Entregar medicina e escoltar até ponto seguro; recompensa de suprimentos e reputação quando concluído. |
| **Última defesa** | Grupo guarda barricada e defende aliados. | Avisar sobre aproximação, pedir apoio, comentar perdas. | Segurar a posição por um intervalo; resultado conforme sobreviventes e presença do jogador. |
| **Falso sinal de rádio** | Looters guardam emboscada; podem ameaçar e recuar. | Exigir suprimentos, blefar, negociar ou encerrar conversa. | Objetivo opcional de descobrir a armadilha ou recuperar carga; sem comércio amigável automático. |

O **Comboio abandonado** pode ser o primeiro encontro novo sem NPC para validar melhorias do gerador de locais em paralelo. Os eventos atuais `cache`, `gunfire`, `flare` e `military` continuam com a lógica existente até uma migração individual. IDs de encontro e atores existentes não devem ser reinterpretados como tarefas novas.

### Dependência de build e carregamento

Publicar as classes `api` no JAR normal do Lazarus NPCs e disponibilizar um artefato de compilação estável para o projeto Events; usar dependência `compileOnly` no build do Events, sem embutir uma segunda cópia da API. Quando o Events passar a chamar a API obrigatoriamente, declarar `lazarusnpcs` com versão mínima compatível em `META-INF/mods.toml` nos dois lados. Registrar provedores durante a fase documentada de inicialização do Forge, não no construtor de entidade. A integração só é liberada depois de uma versão do NPCs com API publicada e instalada no perfil.

## 6. Sequência de implementação

### Etapa 0 — Baseline e contratos

- Registrar versões e hashes dos JARs instalados, salvar backup de teste e identificar mundos de teste. Não modificar mundos em uso para validar migração.
- Mapear fluxos de `NpcIdentity`, `NpcReputation`, `HumanTactics`, `NpcDialogue`, `NpcTasks`, `NpcNetwork`, `NpcClient`, `NpcContracts` e `HumanSurrender`.
- Escrever contrato público Java, tabela de precedência, estados de tarefa, política de morte, limites de rede e esquema de salvamento antes de alterar UI.
- Critério: API documentada com exemplos de uso e casos de rejeição.

### Etapa 1 — Núcleo da API e identidade

- Criar pacote `api`, tipos imutáveis, resultados e consultas; encapsular lógica interna sem mudar comportamento existente.
- Implementar registro de provedores com IDs namespaced, validação de duplicatas e carregamento previsível.
- Cobrir servidor dedicado e ausência de integrador.
- Critério: mod de teste externo compila, consulta um NPC real e recebe erros tipados quando não é aplicável.

### Etapa 2 — Ordens de cenário

- Persistir `ScenarioAssignment`, resolver precedência com contratos/rendição/combate e reutilizar o único objetivo de movimento adequado.
- Implementar atribuição, expiração, retomada após reload e limpeza por proprietário/encontro.
- Medir tick e navegação em encontro com vários NPCs; garantir que barreiras perigosas continuem evitadas.
- Critério: ordens funcionam após relog, não roubam NPC contratado e não alteram IA de infectados.

### Etapa 3 — Diálogos extensíveis

- Definir registro de diálogos, formato de falas estáticas e provedor dinâmico; integrar prioridade de cenário e fallback atual.
- Criar interface de escolhas e pacotes; validar escolha no servidor e aplicar consequência exatamente uma vez.
- Critério: falas por facção mantêm cores/cooldown; um diálogo externo apresenta escolhas, respeita condições e não executa opção expirada.

### Etapa 4 — Tarefas extensíveis e diário

- Introduzir definições e instâncias versionadas de tarefa de encontro; objetivos, progresso, expiração, recompensa e política de morte.
- Adicionar aba **Encontros** ao diário, com layout verificado em resoluções e escalas de GUI diferentes.
- Testar save legado com tarefa comum ativa, inventário cheio, desconexão, morte, NPC morto e provedor removido.
- Critério: tarefa comum e tarefa de encontro coexistem; recompensas não duplicam; texto não sai da tela.

### Etapa 5 — Eventos de resultado e integração

- Publicar eventos Forge observacionais e documentação de payload, ordem e thread.
- Fazer o Lazarus Events consumir a versão publicada da API e implementar **Pedido de socorro** como integração vertical completa.
- Depois implementar **Última defesa** e **Falso sinal de rádio** usando os mesmos contratos; evitar novos atalhos ao NBT do NPCs.
- Critério: cada cenário tem caminho de sucesso, falha, cancelamento e recarga; `/lazarusevents locate/cancel` continua coerente.

### Etapa 6 — Validação, distribuição e documentação

- Compilar ambos os mods, executar testes de servidor e abrir o **cliente completo do modpack**; GameTests isolados não cobrem telas nem conflitos de carregamento entre mods.
- Testar novo mundo e cópia de mundo antigo; singleplayer, servidor dedicado e dois jogadores; provar versionamento e falha clara com JAR errado.
- Medir orçamento de tick, uso de memória e tráfego de UI; revisar logs para falhas de registro, salvamento e recursos ausentes.
- Atualizar READMEs, exemplos de API, changelogs, versões mínimas e instruções de instalação. Instalar os dois JARs compatíveis juntos, sem versões duplicadas no perfil.

## 7. Matriz mínima de testes

| Caso | Resultado exigido |
|---|---|
| NPC recebe ordem e chunk descarrega/recarrega | Retoma ordem válida uma vez; prazo vencido limpa estado. |
| NPC tem contrato ou está rendido | Ordem de cenário é rejeitada ou fica suspensa segundo a precedência documentada. |
| Dois jogadores escolhem a mesma opção/cobram recompensa | Apenas transição válida e pagamento único. |
| Jogador tem missão comum ativa e aceita missão de evento | Ambas aparecem no diário e progridem separadamente. |
| Jogador morre ou sai do servidor | Cada tarefa aplica sua política; não perde nem duplica progresso indevidamente. |
| Mod provedor é removido de uma cópia do mundo | Save carrega; tarefa indisponível pode ser abandonada; sem pagamento automático. |
| Encontro é cancelado ou todos os NPCs morrem | Ordens e tarefas seguem política de encerramento; atores não ficam presos em IA de evento. |
| Cliente envia opção forjada, antiga ou longe do NPC | Servidor recusa sem consumir itens ou alterar reputação. |
| Cliente completo com ambos os mods | Sem crash de carregamento/Mixin, tela legível e protocolos compatíveis. |

## 8. Critério de conclusão da primeira versão

A versão inicial da API estará pronta quando um encontro do Lazarus Events puder **criar um NPC, atribuir comportamento, abrir diálogo com escolhas, oferecer tarefa, acompanhar progresso, concluir ou falhar, recompensar e limpar o cenário**, tudo por contrato público e após reinício do mundo. As tarefas antigas, trocas, contratos, reputação e encontros atuais continuam funcionando. A publicação exige JARs compatíveis de ambos os mods e teste no cliente completo do modpack.

### Referências técnicas

- [Forge 1.20.1 — Events](https://docs.minecraftforge.net/en/1.20.1/concepts/events/): barramento para resultados entre mods.
- [Forge 1.20.x — Mod files](https://docs.minecraftforge.net/en/1.20.x/gettingstarted/modfiles/): declaração de dependência e versão mínima.
- [Forge 1.20.x — Sides](https://docs.minecraftforge.net/en/1.20.x/concepts/sides/): separação entre lógica do servidor e interface do cliente.
