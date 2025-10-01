package org.opencdmp.deposit.fedorarepository.service.fedora;

import org.opencdmp.depositbase.repository.DepositConfiguration;
import org.opencdmp.depositbase.repository.PlanDepositModel;

public interface FedoraDepositService {
	String deposit(PlanDepositModel planDepositModel) throws Exception;

	DepositConfiguration getConfiguration();

	String authenticate(String code);

	String getLogo();
}
