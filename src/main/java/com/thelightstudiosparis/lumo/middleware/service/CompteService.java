package com.thelightstudiosparis.lumo.middleware.service;

import java.io.UnsupportedEncodingException;
import java.util.Calendar;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.mail.internet.AddressException;
import javax.transaction.Transactional;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.thelightstudiosparis.lumo.middleware.authentification.Jeton;
import com.thelightstudiosparis.lumo.middleware.authentification.JetonService;
import com.thelightstudiosparis.lumo.middleware.dao.CompteDao;
import com.thelightstudiosparis.lumo.middleware.dao.DaoException;
import com.thelightstudiosparis.lumo.middleware.dao.DepartementDao;
import com.thelightstudiosparis.lumo.middleware.dao.ProfessionDao;
import com.thelightstudiosparis.lumo.middleware.objetmetier.compte.Compte;
import com.thelightstudiosparis.lumo.middleware.objetmetier.compte.CompteExistantException;
import com.thelightstudiosparis.lumo.middleware.objetmetier.compte.CompteIntrouvableException;
import com.thelightstudiosparis.lumo.middleware.objetmetier.compte.CompteInvalideException;
import com.thelightstudiosparis.lumo.middleware.objetmetier.compte.EmailInvalideException;
import com.thelightstudiosparis.lumo.middleware.objetmetier.departement.Departement;
import com.thelightstudiosparis.lumo.middleware.objetmetier.departement.DepartementIntrouvableException;
import com.thelightstudiosparis.lumo.middleware.objetmetier.membre.Membre;
import com.thelightstudiosparis.lumo.middleware.objetmetier.membre.MembreInvalideException;
import com.thelightstudiosparis.lumo.middleware.objetmetier.profession.Profession;
import com.thelightstudiosparis.lumo.middleware.objetmetier.profession.ProfessionIntrouvableException;



@Stateless
@Transactional
public class CompteService {
	
	
	@EJB
	private CompteDao compteDao;
	
	@EJB
	private DepartementDao departementDao;
	
	@EJB
	private ProfessionDao professionDao;
	
	@EJB
	private JetonService jetonService;
	
	private static final Logger LOGGER =
			LoggerFactory.getLogger(CompteService.class);
	
	/**
	 * créer un compte, avec ses relations en cascade :
	 * - création d'un membre
	 * - ajout dans liste des départements et professions liées.
	 * 
	 * @param compte
	 * @throws CompteInvalideException
	 * @throws CompteExistantException
	 * @throws EmailInvalideException
	 * @throws MembreInvalideException
	 * @throws DepartementIntrouvableException
	 * @throws ProfessionIntrouvableException
	 * @throws DaoException si la vérification qu'un compte existe a échoué côté base de données.
	 */
	public void creerCompte(final Compte compte) 
			throws CompteInvalideException, CompteExistantException, EmailInvalideException,
			MembreInvalideException, DepartementIntrouvableException, ProfessionIntrouvableException, DaoException {
		
		this.validerCompte(compte);
		
		// définir la date de création.
		compte.setDateDeCreation(Calendar.getInstance());
		
		Membre membre = compte.getMembre();
		this.validerMembre(membre);
		
		// ajouter le nouveau membre à la liste des départements.
		for(Departement departement : membre.getListeDepartements()) {
			
			Departement departementAModifier = departementDao.obtenirDepartement(departement.getNumero());
			departementAModifier.getListeMembres().add(membre);
		}
		
		// ajouter le nouveau membre à la liste des professions.
		for(Profession profession : membre.getListeProfessions()) {
			
			Profession professionAModifier = professionDao.obtenirProfession(profession.getId());
			professionAModifier.getListeMembres().add(membre);
		}
		
		// persister le compte (ce qui persiste les modifs en cascade).
		compteDao.creerCompte(compte);
	}
	
	/**
	 * connecter un compte : vérifier son email et mot de passe.
	 * 
	 * @param compte le compte contenant les crédentiels.
	 * @return un objet Jeton
	 * @throws CompteIntrouvableException si aucun compte n'existe pour cet email.
	 * @throws DaoException si problème entre l'orm et la bdd.
	 * @throws CompteInvalideException si la validation des crédentiels échoue.
	 * @throws UnsupportedEncodingException
	 */
	public Jeton connecterCompte(Compte compte) 
			throws CompteIntrouvableException, DaoException, CompteInvalideException, UnsupportedEncodingException {
		
		String email = compte.getEmail();
		String password = compte.getPassword();
		
		Compte compteDeReference = compteDao.obtenirCompte(email);
		
		Jeton jeton = null;
		
		if(compteDeReference.getPassword().equals(password) && compteDeReference.getEmail().equals(email)) {
			
			Membre membre = compteDeReference.getMembre();
			String token = jetonService.creerToken(membre);
			
			// je crée un jeton contenant l'uuid du membre, son prénom et un token d'authentification.
			jeton = new Jeton(membre.getUuid(), membre.getPrenom(), token);
		}
		
		else{
			throw new CompteInvalideException("email ou mot de passe invalide.");
		}
		
		return jeton;
	}
	
	
	
	/**
	 * valider le compte : non null, password non blank, email valide, et email unique.
	 * 
	 * @param compte
	 * @throws CompteInvalideException si le compte est null ou password blank.
	 * @throws EmailInvalideException si l'email n'est pas formaté correctement.
	 * @throws CompteExistantException si un compte existe déjà pour cet email.
	 * @throws DaoException 
	 */
	private void validerCompte(final Compte compte) throws CompteInvalideException, EmailInvalideException, CompteExistantException, DaoException {
		
		if (Objects.isNull(compte))
			throw new CompteInvalideException("Le compte ne peut être null.");
		
		if (StringUtils.isBlank(compte.getPassword()))
			throw new CompteInvalideException("Le mot de passe du compte ne peut valoir null/blanc.");
		
		// vérifier que l'email est valide.
		this.validerEmail(compte.getEmail());
		
		// vérifier si un compte existe déjà à cet email.
		Boolean compteExistant = compteDao.contenirCompte(compte);
		
		if(compteExistant) {
			throw new CompteExistantException("un compte existe déjà pour cet email.");
		}
		
	}
	
	
	private void validerMembre(final Membre membre) throws MembreInvalideException {
		
		if (Objects.isNull(membre))
			throw new MembreInvalideException("Le membre ne peut être null");
		
		// validation de tous les champs à ajouter.
		
	}
	
	/** 
	 * regex de validation du mail.
	 * 
	 * @param email
	 * @throws EmailInvalideException
	 */
	private void validerEmail(final String email) throws EmailInvalideException {
		
		if (StringUtils.isBlank(email)) {
			throw new EmailInvalideException("l'email ne peut être vide.");
		}
		
		Boolean emailValide = Pattern.matches("^[\\w!#$%&’*+/=?`{|}~^-]+(?:\\.[\\w!#$%&’*+/=?`{|}~^-]+)*@(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,6}$", email);
		
		if(!emailValide) {
			throw new EmailInvalideException("vous devez saisir un email valide.");
		}
	}
	
	
	
}
