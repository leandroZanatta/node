import json, sys
from collections import defaultdict, Counter

def load_lances(path):
    lances = []
    with open(path) as f:
        for line in f:
            lances.append(json.loads(line))
    return lances

def resumir_financeiro(lances):
    """Resumo financeiro dos lances incluídos"""
    rotas = defaultdict(list)
    for d in lances:
        envio = d.get('envio', {})
        rota_id = envio.get('rota_id', 'sem-rota')
        lance = envio.get('lance', {})
        rotas[rota_id].append({
            'd': d,
            'alvo': int(lance.get('alvo_wei_por_gas', '0') or 0),
            'ofertado': int(lance.get('ofertado_total_wei_por_gas', '0') or 0),
            'pagamento': int(lance.get('pagamento_direto_builder_bnb_wei', '0') or 0),
            'pagamento_bnb': int(lance.get('pagamento_direto_builder_ativo_raiz_wei', '0') or 0),
            'limitado_lucro': lance.get('limitado_por_lucro', False),
            'limitado_saldo': lance.get('limitado_por_saldo', False),
            'solicitado': lance.get('solicitado_ao_builder', False),
            'lucro_previsto': int(lance.get('lucro_previsto_ativo_wei', '0') or 0),
            'desfecho': d.get('desfecho', {}).get('classe', 'desconhecido'),
            'submissao_aceita': envio.get('submissao_aceita', False),
            'relay_aceitou': envio.get('relay_aceitou', False),
            'saida_prevista': int(lance.get('saida_prevista_wei', '0') or 0),
            'montante_entrada': int(lance.get('montante_entrada_wei', '0') or 0),
            'preco_gas_proprio': int(lance.get('preco_gas_proprio_wei_por_gas', '0') or 0),
            'estimativa_gas': int(lance.get('estimativa_gas_para_bid', '0') or 0),
        })
    
    vencedores = []
    perdedores = []
    for rota_id, lance_list in rotas.items():
        for lance in lance_list:
            if lance['desfecho'] == 'incluida' or lance['submissao_aceita'] or lance['relay_aceitou']:
                vencedores.append((rota_id, lance))
            else:
                perdedores.append((rota_id, lance))
    
    print(f"=== RESUMO FINANCIARIO ===")
    print(f"Total lances: {len(lances)}")
    print(f"Lances incluídos: {len(vencedores)}")
    print(f"Lances perdidos: {len(perdedores)}")
    
    # Pagamentos
    pagamentos = [l['pagamento'] for _, l in vencedores if l['pagamento'] > 0]
    pagamentos_bnb = [l['pagamento_bnb'] for _, l in vencedores if l['pagamento_bnb'] > 0]
    total_pagamento = sum(pagamentos)
    total_pagamento_bnb = sum(pagamentos_bnb)
    print(f"\nPagamentos diretos ao builder (wei): {total_pagamento:,}")
    print(f"Pagamentos diretos ao builder (BNB): {total_pagamento_bnb/1e18:.4f} BNB")
    print(f"Pagamentos diretos ao builder (Gwei): {total_pagamento/1e9:.2f} Gwei")
    
    # Alvo e oferta
    alvos = [l['alvo'] for _, l in vencedores if l['alvo'] > 0]
    ofertados = [l['ofertado'] for _, l in vencedores if l['ofertado'] > 0]
    total_alvo = sum(alvos)
    total_ofertado = sum(ofertados)
    print(f"\nAlvo/gas (wei): {total_alvo:,}")
    print(f"Ofertado/gas (wei): {total_ofertado:,}")
    print(f"Diferença alvo - oferta: {total_alvo - total_ofertado:,} wei")
    if total_alvo > 0:
        print(f"Oferta/Alvo: {total_ofertado/total_alvo*100:.1f}%")
    
    # Gas estimado
    gases = [l['estimativa_gas'] for _, l in vencedores if l['estimativa_gas'] > 0]
    if gases:
        print(f"Gas estimado médio: {sum(gases)/len(gases):.0f} gas")
    
    # Lances limitados
    limitados = [(r, l) for r, l in vencedores if l['limitado_lucro'] or l['limitado_saldo']]
    normal = [(r, l) for r, l in vencedores if not l['limitado_lucro'] and not l['limitado_saldo']]
    print(f"\nLances limitados (lucro/saldo): {len(limitados)}")
    print(f"Lances normais: {len(normal)}")
    
    # Contar limitados por tipo
    lucro_lim = [l for _, l in limitados if l['limitado_lucro']]
    saldo_lim = [l for _, l in limitados if l['limitado_saldo']]
    print(f"  Limitados por lucro: {len(lucro_lim)}")
    print(f"  Limitados por saldo: {len(saldo_lim)}")
    
    # Analisar os limitados por lucro: o que o alvo poderia ser se não fosse limitado?
    print(f"\n=== LANCES COMPLETAMENTO LUCRO LIMITE (alvo > 0) ===")
    completos = [(r, l) for r, l in limitados if l['alvo'] > 0]
    for i, (r, l) in enumerate(completos[:15], 1):
        gas_estimado = l['estimativa_gas'] if l['estimativa_gas'] > 0 else 1
        alvo_max = l['lucro_previsto'] / gas_estimado if gas_estimado > 0 else 0
        print(f"  {i}. {r[:38]}: alvo_atual={l['alvo']:,} (={l['alvo']/1e9:.2f} gwei) "
              f"| lucro_previsto={l['lucro_previsto']:,} | max_alvo_estimado={alvo_max:,.0f} (={alvo_max/1e9:.2f} gwei) "
              f"| pagamento={l['pagamento']:,}")
    
    # Lances com alvo=0 (perderam o lance toda )
    zeros = [(r, l) for r, l in limitados if l['alvo'] == 0]
    print(f"\nLances com alvo=0 (lance zerado): {len(zeros)}")
    for i, (r, l) in enumerate(zeros[:10], 1):
        print(f"  {i}. {r[:38]}: ofertado={l['ofertado']:,} pag={l['pagamento']:,} stopo={l['saida_prevista']:,}")
    
    # Comparativo com o rival
    print(f"\n=== COMPARATIVO LANÇES COM MAIOR DIFERENÇA ===")
    comparativos = []
    for rota_id, lance_list in rotas.items():
        vencedor = [l for l in lance_list if l['desfecho'] == 'incluida' or l['submissao_aceita'] or l['relay_aceitou']]
        perdedor = [l for l in lance_list if not (l['desfecho'] == 'incluida' or l['submissao_aceita'] or l['relay_aceitou'])]
        if vencedor and perdedor:
            v = max(vencedor, key=lambda l: l['pagamento'])
            p = max(perdedor, key=lambda l: l['pagamento'])
            if v['pagamento'] > 0 and p['pagamento'] > 0:
                dif = v['pagamento'] - p['pagamento']
                if dif > 0:
                    comparativos.append((dif, rota_id, v, p))
    comparativos.sort(key=lambda x: -x[0])
    print(f"Total de comparativos com diferença: {len(comparativos)}")
    for i, (dif, rota_id, v, p) in enumerate(comparativos[:10], 1):
        print(f"  {i}. ROTA {rota_id[:38]}: VENCEDOR pag={v['pagamento']:,} | PERDEDOR pag={p['pagamento']:,} | diferença={dif:,} ({dif/1e9:.2f} gwei)")

if __name__ == '__main__':
    path = sys.argv[1] if len(sys.argv) > 1 else '/opt/node/target/ensaio.jsonl'
    lances = load_lances(path)
    resumir_financeiro(lances)
