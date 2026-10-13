import json, sys
from collections import defaultdict

def load_lances(path):
    lances = []
    with open(path) as f:
        for line in f:
            lances.append(json.loads(line))
    return lances

def analisar_vencedores(lances):
    """Analisar lances incluídos e quem levou, pagamento, diferenca de lance"""
    # Agrupar por rota_id
    rotas = defaultdict(list)
    for d in lances:
        envio = d.get('envio', {})
        rota_id = envio.get('rota_id', 'sem-rota')
        lance = envio.get('lance', {})
        rotas[rota_id].append({
            'd': d,
            'alvo': lance.get('alvo_wei_por_gas', '0'),
            'ofertado': lance.get('ofertado_total_wei_por_gas', '0'),
            'pagamento': lance.get('pagamento_direto_builder_bnb_wei', '0'),
            'pagamento_bnb_gas': lance.get('pagamento_direto_builder_ativo_raiz_wei', '0'),
            'limitado_lucro': lance.get('limitado_por_lucro', False),
            'limitado_saldo': lance.get('limitado_por_saldo', False),
            'solicitado': lance.get('solicitado_ao_builder', False),
            'lucro_previsto': lance.get('lucro_previsto_ativo_wei', '0'),
            'desfecho': d.get('desfecho', {}).get('classe', 'desconhecido'),
            'submissao_aceita': envio.get('submissao_aceita', False),
            'relay_aceitou': envio.get('relay_aceitou', False),
            'montante_entrada': lance.get('montante_entrada_wei', '0'),
            'saida_prevista': lance.get('saida_prevista_wei', '0'),
            'tempo': d.get('tempo', {}),
        })
    
    print(f"Total de rotas: {len(rotas)}")
    print(f"Total de lances: {sum(len(v) for v in rotas.values())}")
    
    # Identificar vencedores (lances que foram incluídos/aceitos)
    vencedores = []
    perdedores = []
    for rota_id, lance_list in rotas.items():
        for lance in lance_list:
            if lance['desfecho'] == 'incluida' or lance['submissao_aceita'] or lance['relay_aceitou']:
                vencedores.append((rota_id, lance))
            else:
                perdedores.append((rota_id, lance))
    
    print(f"\n=== LANCES INCLUÍDOS (vencedores): {len(vencedores)} ===")
    print(f"{'#':<4} {'rota_id':<44} {'alvo/gas':<14} {'ofertado/gas':<14} {'pagamento':<16} {'limite':<18} {'eth_gas':<14}")
    print('-' * 130)
    for i, (rota_id, lance) in enumerate(vencedores, 1):
        limite = ''
        if lance['limitado_lucro']:
            limite += 'LUCRO'
        if lance['limitado_saldo']:
            limite += 'SALDO'
        if not limite:
            limite = 'NORMAL'
        gas = lance['pagamento'] if lance['pagamento'] != '0' else '0'
        print(f"{i:<4} {rota_id[:42]:<44} {lance['alvo']:<14} {lance['ofertado']:<14} {lance['pagamento']:<16} {limite:<18} {gas}")
    
    # Resumo financeiro dos vencedores
    total_pagamento_vencedores = sum(int(l['pagamento']) for _, l in vencedores if l['pagamento'] != '0')
    total_alvo = sum(int(l['alvo']) for _, l in vencedores if l['alvo'] != '0')
    total_ofertado = sum(int(l['ofertado']) for _, l in vencedores if l['ofertado'] != '0')
    print(f"\n=== RESUMO FINANCIARIO DOS LANCES INCLUÍDOS ===")
    print(f"Pagamentos diretos ao builder: {total_pagamento_vencedores:,} wei = {total_pagamento_vencedores/1e18:.4f} BNB")
    print(f"Total alvo/gas: {total_alvo:,} wei")
    print(f"Total ofertado/gas: {total_ofertado:,} wei")
    print(f"Diferenca oferta/alvo: {total_ofertado - total_alvo:,} wei")
    
    # Perdedores
    print(f"\n=== LANCES PERDIDOS: {len(perdedores)} ===")
    derrotas = Counter()
    for _, lance in perdedores:
        derrotas[lance['desfecho']] += 1
    for k, v in derrotas.most_common():
        print(f"  {k}: {v}")
    
    # Analisar a diferenca de lance entre vencedor e perdedor (rival_n1, rival_n2)
    print(f"\n=== COMPARATIVO: vencedor vs perdedor (rival_n1/n2) ===")
    for rota_id, lance_list in rotas.items():
        vencedor = [l for l in lance_list if l['desfecho'] == 'incluida' or l['submissao_aceita'] or l['relay_aceitou']]
        perdedor = [l for l in lance_list if not (l['desfecho'] == 'incluida' or l['submissao_aceita'] or l['relay_aceitou'])]
        if vencedor and perdedor:
            v = max(vencedor, key=lambda l: int(l['alvo']))
            p = max(perdedor, key=lambda l: int(l['alvo']))
            dif = int(v['alvo']) - int(p['alvo'])
            if dif > 0:
                print(f"ROTA {rota_id[:38]}: VENCEDOR alvo={v['alvo']} pag={v['pagamento']} | PERDEDOR alvo={p['alvo']} pag={p['pagamento']} | diferenca={dif} wei/gas (vencedor paga {dif/1e9:.2f} gwei mais)")
    
    # Lances limitados por lucro/saldo
    print(f"\n=== LANCES LIMITADOS POR LUCRO OU SALDO (podiam pagar mais?) ===")
    limitados = [l for _, l in vencedores if l['limitado_lucro'] or l['limitado_saldo']]
    for i, lance in enumerate(limitados[:30], 1):
        print(f"  {i}. rota={lance['d'].get('envio',{}).get('rota_id','?')[:38]}: alvo={lance['alvo']} ofertado={lance['ofertado']} pag={lance['pagamento']} "
              f"lucro_limite={lance['limitado_lucro']} saldo_limite={lance['limitado_saldo']} | stopo={lance['d'].get('envio',{}).get('saida_prevista_wei','?')}")
    if len(limitados) > 30:
        print(f"  ... e mais {len(limitados)-30} limitados")

if __name__ == '__main__':
    path = sys.argv[1] if len(sys.argv) > 1 else '/opt/node/target/ensaio.jsonl'
    lances = load_lances(path)
    analisar_vencedores(lances)
