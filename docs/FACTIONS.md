# Adicionando uma facção

As regras compartilhadas vivem em [`NpcFactions`](../src/main/java/com/lazarus/npcs/api/NpcFactions.java). A lista conserva a ordem enviada ao diário e os IDs usados em reputação e saves. Não renomeie um ID existente.

1. Acrescente uma `Definition` com ID, nome, cor, variantes padrão de `survivor`, rivais, `professions=true` caso a facção tenha profissões e uma `Doctrine` com distância preferida, postura, flanqueamento, avanço alternado, limiar de retirada e formação. O construtor sem doutrina usa `Doctrine.DEFAULT`. A relação pode apontar para mais de um rival; declare também o sentido inverso se a rivalidade for mútua.
2. Um NPC comum usa a primeira definição que contém sua variante. Bases e eventos chamam `NpcApi.assignFaction(mob, id)` antes de atribuir a profissão, portanto podem reutilizar uma variante de outra facção. O ID atribuído fica no save do NPC; moradores de base não podem trocar de facção depois.
3. Acrescente as ofertas `id_trader` e `id_armorer` em `default_trades.json`, o arsenal em `NpcGuns`, e texturas `id_profession` para as profissões que terão aparência própria. Sem esses recursos, o NPC mantém as alternativas de comércio/aparência e a tentativa de armá-lo informa falha.
4. Se a facção tiver base, crie sua estrutura `.nbt` e acrescente um `Profile` em `BaseRoster` de Lazarus Events, com postos, depósito, quantidade inicial de comida, abrigo e postos de defesa. Cada vaga define atividades diurnas, `NightDuty` e, para `SLEEP`, o bloco de pé de uma cama real. O gerador deve validar acesso a esses pontos antes de escrever o template. Uma facção nova pode passar `null` como planta legada; o modelo atual será usado nos dois casos. O perfil exige variantes e profissões definidas em NPC's.
5. Bases, expedições, reconhecimento, roubo e confrontos usam o perfil e a relação do catálogo. Confronte o novo par em um mundo de teste; a existência de uma base por facção e a distância mínima entre bases ainda são regras globais.

## Doutrina, rotina e viagens

A `Doctrine` é definida na `NpcFactions.Definition` e regula distância de combate, proporção de flanqueadores, avanço por lances e retirada. Teste a facção em terreno aberto e em ruas estreitas antes de alterar esses números. Os moradores recebem `Routine` pelo `BaseRoster.Slot` do Events: atividades diurnas com pesos, `NightDuty` (`SLEEP`, `SHELTER` ou `WATCH`), cama real, abrigo e posto de defesa. Toda nova planta precisa oferecer caminhos transitáveis para esses pontos. O Events reenvia a rotina após recarga e reposição de moradores.

Expedições usam `ScenarioOrder.TRAVEL` e `NpcApi.setTravelRoute`, com 4 a 8 pontos em chunks já carregados. Mantenha os membros com o mesmo `owner` e `encounterId` para formar um grupo. Os avulsos não podem ter rotina, ordem de cenário ou contrato se devem vasculhar loot; contêineres de base devem receber o marcador `LazarusNpcNoScavenge` nos dados persistentes do bloco ou ser estocados sem `LootTable`.

Mudar a lista de facções exige reconstruir **os dois mods** e usar as mesmas versões no cliente e servidor. NPC's usa protocolo de interface 9; a ordem da lista altera a apresentação do diário. Saves antigos mantêm as facções e variantes conhecidas. Confrontos anteriores com os campos `Rovers`/`Wardens` são lidos e gravados no formato de lados genéricos.
