package org.opencdmp.deposit.fedorarepository.service.fedora;

import org.opencdmp.commonmodels.models.deposit.DepositPreviewModel;
import org.opencdmp.depositbase.repository.DepositConfiguration;
import org.opencdmp.depositbase.repository.PlanDepositModel;

public interface FedoraDepositService {
	String deposit(PlanDepositModel planDepositModel);

	DepositConfiguration getConfiguration();

	String authenticate(String code);

	DepositPreviewModel preview(PlanDepositModel planDepositModel);

	String getLogo();
}
