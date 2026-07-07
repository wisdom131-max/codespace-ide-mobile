import {
  Column, CreateDateColumn, Entity, ManyToOne,
  PrimaryGeneratedColumn, Unique, UpdateDateColumn,
} from 'typeorm';
import { User } from '../users/user.entity';

/** One row per (user, service) — an encrypted OAuth token grant for a connected external service. */
@Entity('connector_tokens')
@Unique(['ownerId', 'service'])
export class ConnectorToken {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @ManyToOne(() => User, { onDelete: 'CASCADE', nullable: false })
  owner: User;

  @Column()
  ownerId: string;

  /** 'gmail' | 'gcalendar' | 'gdrive' | 'slack' — see connector-registry.ts */
  @Column()
  service: string;

  @Column({ type: 'text' })
  accessTokenEnc: string;

  @Column({ type: 'text', nullable: true })
  refreshTokenEnc?: string;

  @Column({ type: 'timestamptz', nullable: true })
  expiresAt?: Date;

  @Column({ nullable: true })
  scope?: string;

  @CreateDateColumn()
  createdAt: Date;

  @UpdateDateColumn()
  updatedAt: Date;
}
