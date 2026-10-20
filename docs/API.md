# API de integração do Lazarus NPCs — versão 5

Pacote público `com.lazarus.npcs.api`, incluído no JAR normal do Lazarus NPCs a partir da **1.5.0** (`grantPassage` desde a **1.6.0**; relações políticas e armamento de cenários desde a **1.7.0**; rotinas desde a **1.13.0**). Outros mods usam os NPCs humanos do Apocalypse Now para criar encontros com comportamento, falas, escolhas, tarefas, recompensas e consequências, sem ler NBT interno nem depender de classes privadas.

Desde a **1.8.0**, integrações podem atribuir uma profissão persistente e uma rota de patrulha vinculada à sua própria ordem. IDs: `lazarusnpcs:leader`, `guard`, `armorer`, `trader`, `medic` (desde 1.9.0: oferece a estabilização dentro da base) e `expedition_soldier`. Para uma profissão de base, forneça o UUID da base; outra base não pode reassumir aquele NPC. Rovers/Wardens comuns sem atribuição explícita usam o perfil de comerciante, preservando as interações anteriores.

## Dependência

```groovy
// build.gradle do mod integrador: compilar contra o JAR do Lazarus NPCs, sem embuti-lo
repositories { flatDir { dirs 'libs' } }
dependencies { compileOnly fg.deobf("blank:lazarusnpcs:1.13.0") }
```

```toml
# META-INF/mods.toml do integrador
[[dependencies.seumod]]
    modId="lazarusnpcs"
    mandatory=true
    versionRange="[1.13.0,)"
    ordering="AFTER"
    side="BOTH"
```

`NpcApi.API_VERSION` vale `5`. A versão 5 acrescenta a doutrina `NpcFactions.Definition.doctrine()`, `NpcApi.setRoutine`, `ScenarioOrder.TRAVEL` e `NpcApi.setTravelRoute`. O construtor anterior de seis argumentos de `NpcFactions.Definition` continua disponível. A versão 4 acrescentou identidade explícita de facção e o catálogo `NpcFactions`; a versão 3 acrescentou profissão persistente e rota de patrulha. As chamadas anteriores preservam seu contrato.

```java
NpcApi.assignProfession(resident, id("leader"), baseId);
NpcApi.profession(resident); // Optional<ResourceLocation>
NpcApi.assign(guard, guardAreaOrder);
NpcApi.setPatrolRoute(guard, ownerId, baseId, List.of(postA, postB, postC));
```

`setPatrolRoute` aceita de 2 a 8 pontos dentro do raio da ordem `GUARD_AREA` do mesmo dono e encontro. A rota é salva na entidade e cede a combate, rendição e contratos. Profissões de base e estoque nunca devem ser inferidos a partir de tags de entidade do mod integrador.

### Viagem em expedição (API v5, NPCs 1.13.0)

```java
NpcApi.assign(scout, ScenarioAssignment.at(owner, encounterId, ScenarioOrder.TRAVEL,
        GlobalPos.of(level.dimension(), start), 16, 12_000));
NpcApi.setTravelRoute(scout, owner, encounterId, List.of(start, p2, p3, finish));
```

Use a mesma ordem e rota para os demais membros do grupo. A rota aceita de 4 a 8 pontos carregados, na mesma altura aproximada do NPC. O primeiro membro por UUID é o batedor; os demais acompanham sua posição. Combate e contratos suspendem o deslocamento, que é retomado depois. A navegação não carrega chunks; o mod integrador deve encerrar a expedição quando o destino sair da área carregada ou vencer o prazo. Atribuir uma ordem de outro encontro limpa a rota anterior.

### Rotina de base (desde 1.13.0)

```java
var routine = new Routine(baseId,
        List.of(new Activity(Activity.Kind.WORK, workPost, 5),
                new Activity(Activity.Kind.SOCIAL, meetingPoint, 2),
                new Activity(Activity.Kind.EAT, canteen, 1)),
        NightDuty.SLEEP, bedFoot, shelter, defensePost);
NpcApi.setRoutine(resident, routine);
```

`Routine`, `Activity` e `NightDuty` ficam em `com.lazarus.npcs.api.ai`. A rotina é transitória: reenvie no carregamento do chunk e na renovação das ordens. `WATCH` mantém o posto à noite e defende a base sob alerta; civis procuram abrigo. O alerta dura 600 ticks após um ataque e é compartilhado por todos os moradores com o mesmo `baseId`. Para `SLEEP`, informe o bloco de pé de uma cama; as atividades aceitam de 1 a 8 destinos carregados, até 64 blocos do NPC. Contratos e combate têm prioridade.

## Threads e fases

| Chamada | Onde |
|---|---|
| `registerDialogue`, `registerTask` | Durante o carregamento do mod (`FMLCommonSetupEvent`, `enqueueWork`). Uma vez por ID. |
| Todas as outras | Na **thread do servidor lógico**. Fora dela retornam `WRONG_THREAD` (consultas retornam vazio). |

Rejeições normais nunca lançam exceção: voltam como `ApiResult` com `status` e `message`.

| Status | Quando |
|---|---|
| `OK` | Feito. `value` e `id` trazem o resultado (nova reputação, ID da instância). |
| `UNSUPPORTED_NPC` | A entidade não é um NPC humano tratado pelo Lazarus NPCs. |
| `BUSY` | NPC contratado por um jogador, rendido, com ordem de outro encontro de prioridade igual ou maior; jogador com outra tarefa de encontro; tarefa do mesmo NPC já aceita por outro jogador. |
| `EXPIRED` | Instância inexistente, já encerrada ou vencida. |
| `INVALID_CONTEXT` | Entidade morta, dimensão errada, valor fora dos limites, alvo ausente, mod dono não carregado. |
| `MISSING_DEFINITION` | Diálogo ou tarefa não registrado (inclusive mod removido). |
| `DUPLICATE` | ID já registrado, ou recompensa de reputação já paga para o encontro. |
| `LIMIT` | Mais de 10 pontos de reputação por chamada, contador fora de 1–1000, recompensas acima do limite. |
| `WRONG_THREAD` | Chamada fora da thread do servidor. |

## 1. Identidade e reputação

```java
NpcApi.identity(entity)                    // Optional<NpcIdentityView>: uuid, name, role, faction, interactive
NpcApi.assignFaction(survivor, "wardens")  // identidade explícita; não depende da variante da skin
NpcApi.reputation(player, "haven")         // -100 a 100
NpcApi.adjustReputation(player, "haven", 5, id("rescue_success"), encounterId)
NpcApi.areRivals("rovers", "wardens")       // true
NpcApi.adjustPoliticalReputation(player, "rovers", 8, id("faction_mission"), missionId)
```

- Papéis: `survivor`, `hunter`, `medic`, `military`; hostis: `looter`, `soldier`, `cannibal` (facção `hostile`).
- `adjustReputation` aceita no máximo **10 pontos por chamada**, exige um `reason` e passa pelas regras normais (limiar de inimigo em -40, fim de contratos, evento). Com `encounterId`, o mesmo motivo paga **uma vez por jogador e encontro**; a segunda chamada retorna `DUPLICATE`. Toda alteração é registrada no log como `[api] reputation ...`.
- `adjustPoliticalReputation` usa a mesma validação e deduplicação. Cada rival definido em `NpcFactions` recebe, por padrão, 25% do ganho ou perda efetiva com sinal oposto; frações são acumuladas separadamente por par. O percentual pode ser alterado em `config/lazarusnpcs-common.toml` (`factions.rivalReputationPercent`, 0–100). Comércio, dano e comandos continuam usando `adjustReputation` sem efeito indireto. A parcela política fica registrada separadamente para que a recuperação diária de hostilidade comum não a apague. Consulte [o guia para adicionar facções](FACTIONS.md).

### Passagem por hostis (desde 1.6.0)

```java
NpcApi.grantPassage(player, looter, 2_400, id("radio_bribe"));
```

Faz o grupo de saqueadores ou soldados desertores (membros a até 16 blocos) deixar o jogador em paz por 20 a 6 000 ticks, como depois de pagar o pedágio. Canibais nunca aceitam (`UNSUPPORTED_NPC`). Para os outros jogadores, o aviso e a negociação do grupo continuam iguais.

## 2. Ordens de cenário

```java
var order = ScenarioAssignment.at(id("rescue"), encounterId, ScenarioOrder.WAIT,
        GlobalPos.of(level.dimension(), pos), 2, 72_000);
ApiResult result = NpcApi.assign(survivor, order);
NpcApi.assign(survivor, ScenarioAssignment.at(id("rescue"), encounterId, ScenarioOrder.FOLLOW_PLAYER, anchor, 4, 24_000)
        .withTarget(player.getUUID()));
NpcApi.clearAssignment(survivor, id("rescue"), encounterId);
NpcApi.armScenarioActor(survivor, id("faction_skirmish"), encounterId);
```

| Ordem | Comportamento |
|---|---|
| `GUARD_AREA` | Fica a até 3 blocos da âncora e combate ameaças dentro do raio + 2. |
| `DEFEND_ALLY` | Fica perto do alvo (NPC ou jogador) e ataca quem o ferir. Exige `target`. |
| `FOLLOW_PLAYER` | Segue o jogador alvo de 3 a 5 blocos, sem empurrar nem teleportar. Exige `target`. |
| `WAIT` | Fica na âncora. Ainda se defende. |
| `RETREAT` | Vai até a âncora (ponto seguro) mais rápido e fica lá. |

**Precedência** (da maior para a menor): morte ou remoção e rendição → combate imediato e defesa própria → contrato de escolta ou guarda de um jogador → recuo tático (vigia, posto, cobertura, perseguição) → ordem de cenário → rotina (passeio). A ordem de cenário cede sozinha sempre que uma regra acima vale e retoma depois. Ela **nunca** toma um NPC contratado nem faz um NPC hostil negociar.

**Limites:** raio 1–32; duração 20–168 000 ticks de mundo; prioridade 0–9 (uma ordem de outro encontro só substitui a atual com prioridade **maior**); o namespace de `owner` precisa ser um mod carregado; a âncora precisa estar na dimensão do NPC.

`armScenarioActor` exige uma ordem vigente com o mesmo dono e ID e aceita somente Rovers ou Wardens. Equipa uma arma do arsenal da própria facção e usa as regras existentes de tiro, dano e drop. Foi acrescentado para o confronto controlado; uma ordem comum não arma o NPC automaticamente.

**Persistência:** a ordem fica salva na própria entidade (`LazarusNpcsScenario`), sobrevive a chunk descarregado, saída do jogo e reinício, e é revalidada quando a entidade carrega. Prazo vencido termina com `EXPIRED`; dono ausente (mod removido) termina com `OWNER_MISSING`; morte com `DIED`; remoção com `REMOVED`. A navegação é a mesma dos outros comportamentos, então barreiras perigosas (`spike_barrier`, `barbed_wire` e a tag `lazarusnpcs:npc_hazards`) continuam evitadas. Cada NPC recebe um único `Goal` de cenário, adicionado uma vez por instância; a verificação periódica roda uma vez por segundo e só em NPCs com ordem.

## 3. Diálogos e escolhas

```java
NpcApi.registerDialogue(new DialogueDefinition(id("rescue_plea"), Set.of(DialogueTrigger.INTERACT), 50, ctx -> {
    if (!isMyRescue(ctx.npc(), ctx.encounterId())) return Optional.empty();
    return Optional.of(new DialoguePage(Component.translatable("lazarusevents.rescue.plea"), List.of(
            new DialogueOption("help", Component.literal("I'll help you"), c ->
                    NpcApi.offerTask(c.player(), c.npc(), id("rescue_survivor"), c.encounterId(), safePoint)),
            new DialogueOption("leave", Component.literal("Not now"), c -> {}))));
}));
```

- Gatilhos: `GREETING`, `INTERACT`, `TASK_OFFER`, `TASK_PROGRESS`, `TASK_COMPLETE`, `THREAT`, `FAREWELL`. O Lazarus NPCs abre `INTERACT` (interação com a mão vazia) e `TASK_OFFER` (ao oferecer tarefa); os demais são abertos pelo integrador com `NpcApi.openDialogue`.
- Definições são consultadas em ordem decrescente de prioridade; a primeira que devolver página é exibida. Sem página, a interação atual (tela do NPC, negociação, rendição) continua igual. As tarefas de encontro usam prioridade 100.
- **Página:** fala de até 300 caracteres e de 1 a 4 opções com IDs únicos (até 32 caracteres). A tela sempre acrescenta **Back**. Textos viajam como componentes JSON, então chaves de tradução são resolvidas no idioma do cliente. Página malformada é ignorada e registrada no log.
- **Escolha:** o cliente envia só a posição da opção e o token da conversa. O servidor só aceita a escolha com token correto, dentro de 1 minuto, com o NPC vivo a até 8 blocos e à vista. A opção ainda precisa ser oferecida pelo provedor no momento da escolha, e a sessão é consumida antes de a consequência rodar. Pacote repetido, token antigo, opção expirada ou jogador longe não fazem nada. A consequência roda uma vez; exceções nela são registradas no log e não derrubam o servidor. Itens cobrados devem ser verificados antes de retirados.
- O provedor deve ser barato e sem efeitos colaterais (pode ser chamado mais de uma vez para revalidar).
- **Filtre pelo seu próprio cenário.** Os provedores de todos os mods são consultados para qualquer NPC. Confira o `owner` da ordem do NPC (`NpcApi.assignment(npc)`) ou o seu `encounterId` e devolva vazio para o resto; do contrário, a sua conversa aparece nos NPCs dos encontros de outro mod.
- **Ouvintes de eventos:** use nomes de método diferentes em cada classe ouvinte do seu mod. O barramento de eventos do Forge 47 confunde manipuladores gerados para métodos de mesmo nome no mesmo evento (`ClassCastException`).

## 4. Tarefas de encontro

```java
NpcApi.registerTask(new TaskDefinition(id("rescue_survivor"), title, description,
        TaskObjectives.sequence(TaskObjectives.deliver(List.of(new ItemStack(BANDAGE, 2))), TaskObjectives.escort(6)),
        24_000, TaskDefinition.DeathPolicy.PERSIST, true, false,
        List.of(new ItemStack(CANNED_FOOD, 2)), null, 0));
ApiResult offer = NpcApi.offerTask(player, survivor, id("rescue_survivor"), encounterId, safePoint);
NpcApi.progressTask(player, offer.id(), "signals", 1);
NpcApi.finishTask(player, offer.id(), NpcApi.TASK_COMPLETED);
```

- **Objetivos prontos:** `deliver` (entregar ao emissor, tudo ou nada), `reach` (jogador chega ao destino), `protect` (manter o emissor vivo por um tempo), `escort` (levar o emissor até o destino), `kill` (filtro recebe vítima e `encounterId`), `counter` (avançado por `progressTask`) e `sequence` (etapas em ordem; o contador `stage` gera evento ao avançar). Objetivos próprios implementam `TaskObjective` sem estado próprio: tudo fica nos contadores da instância.
- **Estados:** `OFFERED` → `ACTIVE` → (`READY` quando `turnIn`) → `COMPLETED`; ou `FAILED`, `EXPIRED`, `ABANDONED`. A oferta mostra título, descrição e recompensa com **Accept/Decline** e vence em 2 minutos.
- `TaskObjective.turnIn(context)` é chamado antes de pagar uma tarefa em `READY`; retorna `false` se o jogador perdeu o pacote exigido. Pode consumir um item identificado pelo ID da instância. O padrão retorna `true`, preservando tarefas antigas.
- **Coexistência:** a tarefa comum do NPC (`LazarusNpcsTasks.Active`) não muda. O jogador pode ter **uma tarefa comum e uma de encontro** ao mesmo tempo. Uma nova oferta de encontro com outra em andamento retorna `BUSY`; o jogador precisa abandonar a anterior pelo diário. O mesmo NPC e encontro não podem ser aceitos por dois jogadores.
- **Idempotência:** o estado muda e é salvo **antes** do pagamento. Repetir pacote, clicar duas vezes, recarregar o mundo ou chamar `finishTask` de novo não duplica itens nem reputação. Itens que não cabem no inventário caem aos pés do jogador.
- **Morte:** `DeathPolicy.FAIL` falha a tarefa quando o jogador morre; `PERSIST` mantém. `failIfGiverDies` falha quando o emissor morre (também para quem estava offline, pelo registro de mortes do mod).
- **Prazo:** de 1 200 a 168 000 ticks depois de aceitar.
- **Provedor ausente:** se o mod que registrou a tarefa for removido, o save carrega normalmente; a tarefa aparece como indisponível no diário, pode ser abandonada e nunca paga.
- **Armazenamento:** `LazarusNpcsEncounterTasks` no jogador, com `Version`, `Open` e `Last`.
- **Diário:** a aba **Encounters** mostra título, estado, objetivo e progresso, emissor e local, destino, tempo restante e recompensa, com botão para abandonar.

## 5. Eventos

Todos em `MinecraftForge.EVENT_BUS`, na thread do servidor, **depois** da mudança confirmada, e apenas observacionais (não canceláveis). Os handlers podem chamar a API de novo; postagens aninhadas com mais de quatro níveis são descartadas e registradas no log, para impedir laços.

| Evento | Dados |
|---|---|
| `NpcApiEvents.ScenarioStarted` / `ScenarioEnded` | NPC, ordem; motivo do fim (`CLEARED`, `EXPIRED`, `REPLACED`, `DIED`, `REMOVED`, `OWNER_MISSING`). |
| `ScenarioActorHurt` / `ScenarioActorDied` | NPC com ordem, fonte do dano. |
| `DialogueChoice` | Jogador, NPC, ID do diálogo, ID da opção, encontro. |
| `TaskStateChanged` | Jogador, instância, tarefa, encontro, de/para, motivo (`offered`, `accepted`, `declined`, `abandoned`, `completed`, `giver_died`, `player_died`, `expired`, `offer_expired` ou o resultado do integrador). |
| `TaskProgress` | Instância, chave do contador, novo valor. |
| `ReputationChanged` | Jogador, facção, antes/depois, motivo (`null` para as regras internas). |

## 6. Rede e segurança

- Protocolo do canal `lazarusnpcs:ui` na versão **5**. Cliente e servidor com versões diferentes recusam a conexão com mensagem do Forge, em vez de falhar durante o jogo.
- Limites de pacote: componentes até 2 048 caracteres de JSON, até 4 opções, texto do diário de encontros até 1 000 caracteres.
- O cliente nunca envia recompensa, custo, progresso nem ID de opção arbitrário; só a posição da opção, o token e o NPC.

## 7. Diagnóstico

`/lazarusnpcs debug` mostra o custo medido de `scenario orders` e `encounter tasks`, além das rotinas existentes.
