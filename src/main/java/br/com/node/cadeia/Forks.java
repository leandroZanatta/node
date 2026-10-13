package br.com.node.cadeia;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.CRC32;

import br.com.node.vo.ForkId;

public final class Forks {

	private Forks() {
	}

	// O fork id do corte de AGORA. É este que vai no Status: anunciar o do genesis
	// é incompatível com a rede de hoje, e o par desconecta sem mensagem — medido
	// no projeto anterior, 23 Status trocados e zero fork ids compatíveis.
	public static ForkId doBsc(long cabeca, long agora) {

		CRC32 crc = new CRC32();

		crc.update(ParametrosBsc.GENESIS);

		for (long fork : ParametrosBsc.FORKS_POR_BLOCO) {

			if (fork > cabeca) {
				return new ForkId(bigEndian32(crc.getValue()), fork);
			}

			crc.update(bigEndian64(fork));
		}

		for (long fork : ParametrosBsc.FORKS_POR_TEMPO) {

			if (fork > agora) {
				return new ForkId(bigEndian32(crc.getValue()), fork);
			}

			crc.update(bigEndian64(fork));
		}

		return new ForkId(bigEndian32(crc.getValue()), 0);
	}

	// Todos os cortes que a BSC já teve. Um par pode estar parado em qualquer um
	// deles — o bootnode publica o do genesis porque não segue a cadeia — então
	// todos valem como prova de rede.
	public static Set<Long> validosDaBsc() {

		Set<Long> hashes = new LinkedHashSet<>();

		CRC32 crc = new CRC32();

		crc.update(ParametrosBsc.GENESIS);
		hashes.add(crc.getValue());

		for (long fork : ParametrosBsc.FORKS_POR_BLOCO) {
			crc.update(bigEndian64(fork));
			hashes.add(crc.getValue());
		}

		for (long fork : ParametrosBsc.FORKS_POR_TEMPO) {
			crc.update(bigEndian64(fork));
			hashes.add(crc.getValue());
		}

		return hashes;
	}

	private static byte[] bigEndian32(long valor) {

		return new byte[] { (byte) (valor >>> 24), (byte) (valor >>> 16), (byte) (valor >>> 8), (byte) valor };
	}

	private static byte[] bigEndian64(long valor) {

		byte[] bytes = new byte[8];

		for (int i = 0; i < 8; i++) {
			bytes[i] = (byte) (valor >>> (56 - 8 * i));
		}

		return bytes;
	}
}
