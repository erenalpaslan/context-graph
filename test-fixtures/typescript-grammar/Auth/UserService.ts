import { Logger } from './Logger';
import * as fs from 'fs';

export interface IUserService {
  name: string;
  save(id: string): void;
}

export type UserId = string;

export enum Role {
  Admin,
  Member,
}

@Injectable()
export class UserService implements IUserService {
  private name: string;

  constructor(private logger: Logger) {}

  save(id: string): void {
    this.logger.log(id);
  }

  save(id: string, retries: number): void {
    this.logger.log(id);
  }

  static create(): UserService {
    return new UserService(new Logger());
  }
}
