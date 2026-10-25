package com.whatname.iam.repository;

import com.whatname.iam.entity.Domain.Account;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, Long> {
  Optional<Account> findByUsername(String username);

  Optional<Account> findByPhone(String phone);

  Optional<Account> findByEmployeeNo(String employeeNo);
}
